package com.mugsun.boot.captcha;

import com.mugsun.boot.captcha.dto.ChallengeVO;
import com.mugsun.boot.captcha.dto.TokenVO;
import com.mugsun.boot.captcha.dto.TrackPoint;
import com.mugsun.boot.captcha.dto.VerifyRequest;
import com.mugsun.boot.captcha.image.PuzzleRenderer;
import com.mugsun.boot.captcha.store.CaptchaStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.HexFormat;
import java.util.List;
import java.util.function.LongSupplier;
import java.util.regex.Pattern;

/**
 * 滑块拼图引擎：出题 → 校验（先删后比，一次机会）→ 签发一次性 captchaToken → 业务接口核销。
 * 失败原因对外一律 40011；限流/锁定期间直接 40012，不再进入校验。
 */
@Service
public class SliderCaptchaService {

	private static final Logger log = LoggerFactory.getLogger(SliderCaptchaService.class);

	static final String PREFIX = "mugsun:captcha:v2:";
	static final String K_CHALLENGE = PREFIX + "challenge:";
	static final String K_TOKEN = PREFIX + "token:";
	static final String K_CHALLENGE_RATE = PREFIX + "rl:challenge:";
	static final String K_KEYBOARD_RATE = PREFIX + "rl:keyboard:";
	static final String K_FAIL = PREFIX + "fail:";
	static final String K_LOCK = PREFIX + "lock:";
	static final String K_GLOBAL = PREFIX + "rl:global:";
	static final String K_SMS_PHONE = PREFIX + "rl:sms-phone:";
	static final String K_SMS_IP = PREFIX + "rl:sms-ip:";
	static final String K_SMS_BURST_PHONE = PREFIX + "rl:sms-burst-phone:";
	static final String K_SMS_BURST_IP = PREFIX + "rl:sms-burst-ip:";

	public static final String MODE_POINTER = "pointer";
	public static final String MODE_KEYBOARD = "keyboard";
	public static final String TYPE_SLIDER = "SLIDER";

	private static final Pattern ID = Pattern.compile("^[0-9a-f]{32}$");
	private static final Pattern FIELD = Pattern.compile("^[A-Za-z0-9_-]{1,32}$");
	private static final int MIN_DISPLAY_WIDTH = 260;
	private static final SecureRandom RANDOM = new SecureRandom();

	private final CaptchaStore store;
	private final CaptchaProperties props;
	private final PuzzleRenderer renderer;
	private final CaptchaAuditSink audit;
	private final LongSupplier clock;

	@Autowired
	public SliderCaptchaService(CaptchaStore store, CaptchaProperties props, PuzzleRenderer renderer,
								CaptchaAuditSink audit) {
		this(store, props, renderer, audit, System::currentTimeMillis);
	}

	public SliderCaptchaService(CaptchaStore store, CaptchaProperties props, PuzzleRenderer renderer,
								CaptchaAuditSink audit, LongSupplier clock) {
		this.store = store;
		this.props = props;
		this.renderer = renderer;
		this.audit = audit;
		this.clock = clock;
	}

	/** 签发挑战；scene/clientId/tenantId 须为调用方已规范化的值；inputMode 写入记录，verify 时必须一致 */
	public ChallengeVO challenge(String scene, String clientId, String tenantId, String inputMode, String ip) {
		String mode = normalizeMode(inputMode);
		requireField(scene);
		requireField(clientId);
		requireField(tenantId);
		String ipKey = safeIp(ip);
		assertNotLocked(ipKey);
		takeGlobalToken();
		CaptchaProperties.RateLimit rl = props.getRateLimit();
		String rateKey = K_CHALLENGE_RATE + ipKey;
		if (store.increment(rateKey, 60) > rl.getChallengePerMinute()) {
			throw CaptchaException.rateLimited(store.ttlSeconds(rateKey));
		}
		PuzzleRenderer.Puzzle p = renderer.render();
		String id = randomHex();
		long ttl = props.getSlider().getExpireSeconds();
		String record = String.join("|", String.valueOf(p.answerX()), scene, clientId, tenantId,
			String.valueOf(clock.getAsLong()), mode);
		store.put(K_CHALLENGE + id, record, ttl);
		Integer step = MODE_KEYBOARD.equals(mode) ? props.getSlider().getKeyboardStepPx() : null;
		return new ChallengeVO(id, TYPE_SLIDER, mode, p.background(), p.piece(), p.bgWidth(), p.bgHeight(),
			p.pieceWidth(), p.pieceHeight(), step, ttl);
	}

	/** 校验：成功返回一次性 token；任何失败该挑战即作废 */
	public TokenVO verify(VerifyRequest req, String ip, String userAgent) {
		String ipKey = safeIp(ip);
		assertNotLocked(ipKey);
		String mode = req == null ? MODE_POINTER : normalizeMode(req.inputMode());
		if (MODE_KEYBOARD.equals(mode)) {
			String kbKey = K_KEYBOARD_RATE + ipKey;
			if (store.increment(kbKey, 60) > props.getRateLimit().getKeyboardVerifyPerMinute()) {
				throw CaptchaException.rateLimited(store.ttlSeconds(kbKey));
			}
		}
		if (req == null || req.captchaId() == null || !ID.matcher(req.captchaId()).matches()) {
			throw CaptchaException.expired();
		}
		// 先删后比：并发下只有一个请求能取到挑战
		String raw = store.take(K_CHALLENGE + req.captchaId());
		if (raw == null) {
			throw CaptchaException.expired();
		}
		String[] parts = raw.split("\\|", -1);
		if (parts.length != 6) {
			throw CaptchaException.expired();
		}
		int expectedX = Integer.parseInt(parts[0]);
		long issuedAt = Long.parseLong(parts[4]);
		boolean ok;
		try {
			// 输入方式以出题时声明为准，verify 自报不同即失败（防止临时降级到 keyboard 跳过轨迹校验）
			ok = parts[5].equals(mode) && judge(req, mode, expectedX, issuedAt);
		} catch (RuntimeException e) {
			ok = false;
		}
		if (!ok) {
			// 触发锁定的那次失败直接回 40012 + retryAfter，不再多给一次 40011
			if (recordFailure(ipKey, mode, parts[3])) {
				throw CaptchaException.rateLimited(props.getRateLimit().getLockSeconds());
			}
			// 并发下锁可能由另一请求（或另一输入模式）刚刚设置：只要失败时 IP 已处于锁定，一律 40012
			long left = store.ttlSeconds(K_LOCK + ipKey);
			if (left > 0) {
				throw CaptchaException.rateLimited(left);
			}
			throw CaptchaException.failed();
		}
		String token = randomHex();
		long ttl = props.getSlider().getTokenExpireSeconds();
		store.put(K_TOKEN + token, String.join("|", parts[1], parts[2], parts[3], hash(ipKey), hash(userAgent)), ttl);
		return new TokenVO(token, ttl);
	}

	/** 发短信第〇步：宽松短窗口频率限制（每个请求都计数，含无 token；60 秒窗口到期自动恢复，不会长时间锁死） */
	public void hitSmsBurst(String phone, String ip) {
		CaptchaProperties.RateLimit rl = props.getRateLimit();
		String ipKey = K_SMS_BURST_IP + safeIp(ip);
		if (rl.getSmsIpPerMinute() > 0 && store.increment(ipKey, 60) > rl.getSmsIpPerMinute()) {
			throw CaptchaException.rateLimited(store.ttlSeconds(ipKey));
		}
		String phoneKey = K_SMS_BURST_PHONE + hash(phone);
		if (rl.getSmsPhonePerMinute() > 0 && store.increment(phoneKey, 60) > rl.getSmsPhonePerMinute()) {
			throw CaptchaException.rateLimited(store.ttlSeconds(phoneKey));
		}
	}

	/** 发短信第一步：只读检查单 IP 每小时、单手机号每天配额，不计数（无 token/无效 token 的请求不消耗配额） */
	public void checkSmsQuota(String phone, String ip) {
		CaptchaProperties.RateLimit rl = props.getRateLimit();
		String ipKey = K_SMS_IP + safeIp(ip);
		if (store.count(ipKey) >= rl.getSmsIpPerHour()) {
			throw CaptchaException.rateLimited(store.ttlSeconds(ipKey));
		}
		String phoneKey = K_SMS_PHONE + hash(phone);
		if (store.count(phoneKey) >= rl.getSmsPhonePerDay()) {
			throw CaptchaException.rateLimited(store.ttlSeconds(phoneKey));
		}
	}

	/**
	 * 发短信第三步（token 已核销后）：原子「未超上限才计数」。先 IP 后手机号；
	 * 手机号已满时 IP 计数不回退（偏保守）。并发下每个号码至多 sms-phone-per-day 次通过。
	 */
	public void takeSmsQuota(String phone, String ip) {
		CaptchaProperties.RateLimit rl = props.getRateLimit();
		String ipKey = K_SMS_IP + safeIp(ip);
		if (store.incrementIfBelow(ipKey, rl.getSmsIpPerHour(), 3600) < 0) {
			throw CaptchaException.rateLimited(store.ttlSeconds(ipKey));
		}
		String phoneKey = K_SMS_PHONE + hash(phone);
		if (store.incrementIfBelow(phoneKey, rl.getSmsPhonePerDay(), 86400) < 0) {
			throw CaptchaException.rateLimited(store.ttlSeconds(phoneKey));
		}
	}

	/** 检查 + 计数（无验证码步骤时使用） */
	public void assertSmsQuota(String phone, String ip) {
		checkSmsQuota(phone, ip);
		takeSmsQuota(phone, ip);
	}

	/** 短信下发失败：计数不回退，写审计（号码只记哈希） */
	public void smsSendFailed(String phone, String ip, String reason) {
		audit.smsSendFailed(safeIp(ip), hash(phone), reason);
	}

	/** 仅供测试：当前手机号计数 */
	long smsPhoneCount(String phone) {
		return store.count(K_SMS_PHONE + hash(phone));
	}

	/** 业务接口核销：先删后比，scene/clientId/tenantId 必须严格相等 */
	public void consume(String token, String scene, String clientId, String tenantId, String ip, String userAgent) {
		if (token == null || !ID.matcher(token).matches()) {
			throw CaptchaException.required();
		}
		String raw = store.take(K_TOKEN + token);
		if (raw == null) {
			throw CaptchaException.required();
		}
		String[] p = raw.split("\\|", -1);
		if (p.length != 5 || !p[0].equals(scene) || !p[1].equals(clientId) || !p[2].equals(tenantId)) {
			log.info("captchaToken 绑定不符：scene={} client={} tenant={}", scene, clientId, tenantId);
			throw CaptchaException.required();
		}
		CaptchaProperties.Slider s = props.getSlider();
		if (s.isBindIp() && !p[3].equals(hash(safeIp(ip)))) {
			throw CaptchaException.required();
		}
		if (s.isBindUa() && !p[4].equals(hash(userAgent))) {
			throw CaptchaException.required();
		}
	}

	/** 底图原始宽度（App 兼容层把 moveX 视为原始像素） */
	public int bgWidth() {
		return props.getSlider().getWidth();
	}

	/** 仅供集成测试读取答案（不挂 HTTP，不消费挑战；勿在业务代码中调用） */
	public int peekAnswerX(String captchaId) {
		String raw = store.peek(K_CHALLENGE + captchaId);
		if (raw == null) {
			throw CaptchaException.expired();
		}
		return Integer.parseInt(raw.substring(0, raw.indexOf('|')));
	}

	// ------------------------------------------------------------------ 判定

	private boolean judge(VerifyRequest req, String mode, int expectedX, long issuedAt) {
		CaptchaProperties.Slider s = props.getSlider();
		long serverElapsed = clock.getAsLong() - issuedAt;
		if (serverElapsed < s.getMinDurationMs() || serverElapsed > s.getExpireSeconds() * 1000) {
			return false;
		}
		Integer display = req.bgDisplayWidth();
		int bgW = s.getWidth();
		if (display == null || display < Math.max(MIN_DISPLAY_WIDTH, bgW / 2) || display > bgW * 3) {
			return false;
		}
		if (req.answer() == null || req.answer().x() == null || !Double.isFinite(req.answer().x())) {
			return false;
		}
		double ax = req.answer().x();
		if (ax < 0 || ax > display) {
			return false;
		}
		double scale = (double) bgW / display;
		if (Math.abs(ax * scale - expectedX) > s.getTolerancePx()) {
			return false;
		}
		if (req.startTime() == null || req.endTime() == null) {
			return false;
		}
		long clientDur = req.endTime() - req.startTime();
		// 客户端时间只做一致性：不得长于服务端实际经过时间（留 2s 网络余量）
		if (clientDur < 0 || clientDur > serverElapsed + 2000) {
			return false;
		}
		if (MODE_KEYBOARD.equals(mode)) {
			Integer presses = req.keyPresses();
			if (clientDur < s.getKeyboardMinDurationMs() || clientDur > s.getMaxDurationMs()
				|| presses == null || presses < s.getKeyboardMinKeyPresses() || presses > s.getKeyboardMaxKeyPresses()) {
				return false;
			}
			// 步进式：按键次数 × 服务端步长 必须与落点一致（±1 步）
			int step = Math.max(1, s.getKeyboardStepPx());
			return Math.abs(presses - Math.round(ax * scale / step)) <= 1;
		}
		if (clientDur < s.getMinDurationMs() || clientDur > s.getMaxDurationMs()) {
			return false;
		}
		return trackLooksHuman(req.track(), display, ax, scale, clientDur, s);
	}

	static boolean trackLooksHuman(List<TrackPoint> track, int display, double ax, double scale,
								   long clientDur, CaptchaProperties.Slider s) {
		if (track == null || track.size() < s.getMinTrackPoints() || track.size() > s.getMaxTrackPoints()) {
			return false;
		}
		double minY = Double.MAX_VALUE;
		double maxY = -Double.MAX_VALUE;
		long prevT = -1;
		double prevX = Double.NaN;
		int distinctT = 0;
		double sum = 0;
		double sumSq = 0;
		int n = 0;
		double yLimit = s.getHeight() * 3.0;
		for (TrackPoint p : track) {
			if (p == null || p.x() == null || p.y() == null || p.t() == null
				|| !Double.isFinite(p.x()) || !Double.isFinite(p.y())) {
				return false;
			}
			double x = p.x();
			double y = p.y();
			long t = p.t();
			if (x < -20 || x > display + 20 || Math.abs(y) > yLimit || t < 0 || t > clientDur + 1000 || t < prevT) {
				return false;
			}
			if (!Double.isNaN(prevX)) {
				double dx = x - prevX;
				if (Math.abs(dx) > display / 2.0) {
					return false;
				}
				long dt = t - prevT;
				if (dt > 0) {
					double v = Math.abs(dx) / dt;
					sum += v;
					sumSq += v * v;
					n++;
				}
			}
			if (t != prevT) {
				distinctT++;
			}
			minY = Math.min(minY, y);
			maxY = Math.max(maxY, y);
			prevT = t;
			prevX = x;
		}
		if (distinctT < 3 || n < 2) {
			return false;
		}
		double travel = track.get(track.size() - 1).x() - track.get(0).x();
		if (Math.abs(travel - ax) * scale > s.getTolerancePx()) {
			return false;
		}
		double mean = sum / n;
		double var = Math.max(0, sumSq / n - mean * mean);
		double cv = mean > 0 ? Math.sqrt(var) / mean : 0;
		// 完全水平且匀速：判为脚本
		return !(maxY - minY == 0 && cv < 0.05);
	}

	// ------------------------------------------------------------------ 限流

	private void assertNotLocked(String ipKey) {
		long left = store.ttlSeconds(K_LOCK + ipKey);
		if (left > 0 || store.peek(K_LOCK + ipKey) != null) {
			throw CaptchaException.rateLimited(left);
		}
	}

	/** 记录一次失败；返回 true 表示本次失败触发了锁定 */
	private boolean recordFailure(String ipKey, String mode, String tenantId) {
		CaptchaProperties.RateLimit rl = props.getRateLimit();
		int max = MODE_KEYBOARD.equals(mode) ? rl.getKeyboardFailMax() : rl.getVerifyFailMax();
		long fails = store.increment(K_FAIL + mode + ":" + ipKey, rl.getFailWindowSeconds());
		log.info("滑块校验未通过：ip={} mode={} tenant={} fails={}", ipKey, mode, tenantId, fails);
		if (fails >= max) {
			store.put(K_LOCK + ipKey, mode, rl.getLockSeconds());
			store.delete(K_FAIL + mode + ":" + ipKey);
			audit.locked(ipKey, mode, tenantId, rl.getLockSeconds());
			return true;
		}
		return false;
	}

	private void takeGlobalToken() {
		int cap = props.getRateLimit().getGlobalChallengePerSecond();
		if (cap <= 0) {
			return;
		}
		// 每秒一个计数键：redis 存储时集群共享，memory 存储时为单节点
		long sec = clock.getAsLong() / 1000;
		if (store.increment(K_GLOBAL + sec, 2) > cap) {
			throw CaptchaException.rateLimited(1);
		}
	}

	// ------------------------------------------------------------------ 工具

	private static void requireField(String v) {
		if (v == null || !FIELD.matcher(v).matches()) {
			throw new com.mugsun.core.tool.exception.ServiceException("验证参数无效，请刷新页面后重试");
		}
	}

	static String normalizeMode(String inputMode) {
		return inputMode != null && MODE_KEYBOARD.equalsIgnoreCase(inputMode.trim()) ? MODE_KEYBOARD : MODE_POINTER;
	}

	private static String safeIp(String ip) {
		return ip == null || ip.isBlank() ? "unknown" : ip.trim().replace(':', '_');
	}

	private static String randomHex() {
		byte[] b = new byte[16];
		RANDOM.nextBytes(b);
		return HexFormat.of().formatHex(b);
	}

	static String hash(String v) {
		try {
			byte[] d = MessageDigest.getInstance("SHA-256").digest((v == null ? "" : v).getBytes(StandardCharsets.UTF_8));
			return HexFormat.of().formatHex(d, 0, 8);
		} catch (NoSuchAlgorithmException e) {
			throw new IllegalStateException(e);
		}
	}
}
