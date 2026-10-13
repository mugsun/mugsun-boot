package com.mugsun.boot.captcha;

import com.mugsun.boot.captcha.dto.ChallengeVO;
import com.mugsun.boot.captcha.dto.TokenVO;
import com.mugsun.boot.captcha.dto.TrackPoint;
import com.mugsun.boot.captcha.dto.VerifyRequest;
import com.mugsun.boot.captcha.image.BackgroundLibrary;
import com.mugsun.boot.captcha.image.PuzzleRenderer;
import com.mugsun.boot.captcha.store.CaptchaStore;
import com.mugsun.boot.captcha.store.MemoryCaptchaStore;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 滑块引擎单测（内存存储 + 可控时钟）：一次性、过期、限流、重放、轨迹异常、绑定、并发双花 */
class SliderCaptchaServiceTest {

	private static final String IP = "203.0.113.7";
	private static final String UA = "JUnit";

	private final AtomicLong now = new AtomicLong(1_760_000_000_000L);
	private CaptchaProperties props;
	private CaptchaStore store;
	private SliderCaptchaService svc;
	private final List<String> lockedAudit = new ArrayList<>();

	@BeforeAll
	static void headless() {
		System.setProperty("java.awt.headless", "true");
	}

	@BeforeEach
	void setUp() {
		props = new CaptchaProperties();
		props.getBackground().setDir("classpath:captcha/none-for-test/");
		store = new MemoryCaptchaStore(now::get);
		PuzzleRenderer renderer = new PuzzleRenderer(props, new BackgroundLibrary(props));
		svc = new SliderCaptchaService(store, props, renderer,
			(ip, mode, tenant, sec) -> lockedAudit.add(ip + "/" + mode), now::get);
	}

	// ---------------------------------------------------------------- helpers

	private ChallengeVO challenge() {
		return svc.challenge("login", "web", "000000", "pointer", IP);
	}

	private ChallengeVO keyboardChallenge() {
		return svc.challenge("login", "web", "000000", "keyboard", IP);
	}

	/** 按服务端步长（默认 4）算出的按键次数 */
	private static int presses(int answerX) {
		return Math.round(answerX / 4f);
	}

	/** 人类样轨迹：ease-out 位移 + 纵向抖动，display 像素 */
	private static List<TrackPoint> humanTrack(double travel, long dur) {
		List<TrackPoint> pts = new ArrayList<>();
		int n = 24;
		for (int i = 0; i <= n; i++) {
			double k = (double) i / n;
			double eased = 1 - Math.pow(1 - k, 3);
			pts.add(new TrackPoint(travel * eased, (double) ((i * 7) % 3 - 1), (long) (dur * k)));
		}
		return pts;
	}

	private VerifyRequest pointer(String id, double x, int display, List<TrackPoint> track, long dur) {
		long end = now.get();
		return new VerifyRequest(id, "pointer", display, end - dur, end, track, new VerifyRequest.Answer(x), null);
	}

	private VerifyRequest good(ChallengeVO c) {
		int ax = svc.peekAnswerX(c.captchaId());
		now.addAndGet(900);
		return pointer(c.captchaId(), ax, 320, humanTrack(ax, 850), 850);
	}

	private TokenVO pass() {
		return svc.verify(good(challenge()), IP, UA);
	}

	private static int code(Runnable r) {
		try {
			r.run();
			return 200;
		} catch (CaptchaException e) {
			return e.getCode();
		}
	}

	// ---------------------------------------------------------------- tests

	@Test
	void challengeHasNoAnswerAndPieceHasNoTextChunks() {
		ChallengeVO c = challenge();
		assertThat(c.type()).isEqualTo("SLIDER");
		assertThat(c.expireSeconds()).isEqualTo(120);
		assertThat(c.pieceHeight()).isEqualTo(c.bgHeight());
		byte[] png = Base64.getDecoder().decode(c.piece().substring(c.piece().indexOf(',') + 1));
		String latin = new String(png, StandardCharsets.ISO_8859_1);
		assertThat(latin).doesNotContain("tEXt").doesNotContain("iTXt").doesNotContain("zTXt");
	}

	@Test
	void validPointerPassesAndTokenIsOneTime() {
		TokenVO t = pass();
		svc.consume(t.captchaToken(), "login", "web", "000000", IP, UA);
		assertThat(code(() -> svc.consume(t.captchaToken(), "login", "web", "000000", IP, UA)))
			.isEqualTo(CaptchaCodes.REQUIRED);
	}

	@Test
	void displayScalingUsesAnswerInDisplayPixels() {
		ChallengeVO c = challenge();
		int ax = svc.peekAnswerX(c.captchaId());
		now.addAndGet(900);
		double displayX = ax * 640.0 / 320;
		TokenVO t = svc.verify(pointer(c.captchaId(), displayX, 640, humanTrack(displayX, 850), 850), IP, UA);
		assertThat(t.captchaToken()).hasSize(32);
	}

	@Test
	void challengeHasSingleAttempt() {
		ChallengeVO c = challenge();
		int ax = svc.peekAnswerX(c.captchaId());
		now.addAndGet(900);
		assertThat(code(() -> svc.verify(pointer(c.captchaId(), ax + 40, 320, humanTrack(ax + 40, 850), 850), IP, UA)))
			.isEqualTo(CaptchaCodes.FAILED);
		assertThat(code(() -> svc.verify(pointer(c.captchaId(), ax, 320, humanTrack(ax, 850), 850), IP, UA)))
			.isEqualTo(CaptchaCodes.EXPIRED);
	}

	@Test
	void toleranceIsSixOriginalPixels() {
		ChallengeVO c1 = challenge();
		int a1 = svc.peekAnswerX(c1.captchaId());
		now.addAndGet(900);
		assertThat(code(() -> svc.verify(pointer(c1.captchaId(), a1 + 6, 320, humanTrack(a1 + 6, 850), 850), IP, UA))).isEqualTo(200);
		ChallengeVO c2 = challenge();
		int a2 = svc.peekAnswerX(c2.captchaId());
		now.addAndGet(900);
		assertThat(code(() -> svc.verify(pointer(c2.captchaId(), a2 + 7, 320, humanTrack(a2 + 7, 850), 850), IP, UA)))
			.isEqualTo(CaptchaCodes.FAILED);
	}

	@Test
	void challengeAndTokenExpire() {
		ChallengeVO c = challenge();
		int ax = svc.peekAnswerX(c.captchaId());
		now.addAndGet(121_000);
		assertThat(code(() -> svc.verify(pointer(c.captchaId(), ax, 320, humanTrack(ax, 850), 850), IP, UA)))
			.isEqualTo(CaptchaCodes.EXPIRED);
		TokenVO t = pass();
		now.addAndGet(121_000);
		assertThat(code(() -> svc.consume(t.captchaToken(), "login", "web", "000000", IP, UA)))
			.isEqualTo(CaptchaCodes.REQUIRED);
	}

	@Test
	void serverSideTimerRejectsTooFast() {
		ChallengeVO c = challenge();
		int ax = svc.peekAnswerX(c.captchaId());
		now.addAndGet(200);
		// 客户端谎报 850ms 也不行：服务端只经过 200ms
		assertThat(code(() -> svc.verify(pointer(c.captchaId(), ax, 320, humanTrack(ax, 850), 850), IP, UA)))
			.isEqualTo(CaptchaCodes.FAILED);
	}

	@Test
	void displayWidthBounds() {
		for (int display : new int[]{259, 961}) {
			ChallengeVO c = challenge();
			int ax = svc.peekAnswerX(c.captchaId());
			now.addAndGet(900);
			double x = ax * (double) display / 320;
			assertThat(code(() -> svc.verify(pointer(c.captchaId(), x, display, humanTrack(x, 850), 850), IP, UA)))
				.as("display=" + display).isEqualTo(CaptchaCodes.FAILED);
		}
	}

	@Test
	void robotTracksAreRejected() {
		// 1) 完全水平匀速
		ChallengeVO c1 = challenge();
		int a1 = svc.peekAnswerX(c1.captchaId());
		now.addAndGet(900);
		List<TrackPoint> straight = new ArrayList<>();
		for (int i = 0; i <= 20; i++) {
			straight.add(new TrackPoint(a1 * i / 20.0, 0.0, 40L * i));
		}
		assertThat(code(() -> svc.verify(pointer(c1.captchaId(), a1, 320, straight, 800), IP, UA))).isEqualTo(CaptchaCodes.FAILED);
		// 2) 点数不足
		ChallengeVO c2 = challenge();
		int a2 = svc.peekAnswerX(c2.captchaId());
		now.addAndGet(900);
		assertThat(code(() -> svc.verify(pointer(c2.captchaId(), a2, 320, humanTrack(a2, 850).subList(0, 5), 850), IP, UA)))
			.isEqualTo(CaptchaCodes.FAILED);
		// 3) 轨迹位移与 answer.x 不符（只改 answer 不拖动）
		ChallengeVO c3 = challenge();
		int a3 = svc.peekAnswerX(c3.captchaId());
		now.addAndGet(900);
		assertThat(code(() -> svc.verify(pointer(c3.captchaId(), a3, 320, humanTrack(a3 - 30, 850), 850), IP, UA)))
			.isEqualTo(CaptchaCodes.FAILED);
		// 4) 时间倒流
		ChallengeVO c4 = challenge();
		int a4 = svc.peekAnswerX(c4.captchaId());
		now.addAndGet(900);
		List<TrackPoint> back = new ArrayList<>(humanTrack(a4, 850));
		back.set(5, new TrackPoint(back.get(5).x(), back.get(5).y(), 10_000L));
		assertThat(code(() -> svc.verify(pointer(c4.captchaId(), a4, 320, back, 850), IP, UA))).isEqualTo(CaptchaCodes.FAILED);
	}

	@Test
	void failuresLockIpAndLockedRequestsSkipVerification() {
		for (int i = 0; i < 4; i++) {
			ChallengeVO c = challenge();
			now.addAndGet(900);
			assertThat(code(() -> svc.verify(pointer(c.captchaId(), 1, 320, humanTrack(1, 850), 850), IP, UA)))
				.isEqualTo(CaptchaCodes.FAILED);
		}
		assertThat(lockedAudit).isEmpty();
		// 第 5 次失败即触发锁定：verify 本身直接回 40012 + retryAfter（不再先回一次 40011）
		ChallengeVO c5 = challenge();
		now.addAndGet(900);
		CaptchaException locked = (CaptchaException) catchCaptcha(() ->
			svc.verify(pointer(c5.captchaId(), 1, 320, humanTrack(1, 850), 850), IP, UA));
		assertThat(locked.getCode()).isEqualTo(CaptchaCodes.RATE_LIMITED);
		assertThat((Long) locked.getData().get("retryAfter")).isEqualTo(600L);
		assertThat(lockedAudit).containsExactly("203.0.113.7/pointer");
		try {
			challenge();
		} catch (CaptchaException e) {
			assertThat(e.getCode()).isEqualTo(CaptchaCodes.RATE_LIMITED);
			assertThat(e.getData()).containsKey("retryAfter");
			assertThat((Long) e.getData().get("retryAfter")).isBetween(1L, 600L);
		}
		// 已锁定：即便答案正确也直接 40012，不进入校验、不消耗挑战
		assertThat(code(() -> svc.verify(pointer("0123456789abcdef0123456789abcdef", 1, 320, humanTrack(1, 850), 850), IP, UA)))
			.isEqualTo(CaptchaCodes.RATE_LIMITED);
		// 其他 IP 不受影响
		assertThat(svc.challenge("login", "web", "000000", "pointer", "198.51.100.1").captchaId()).hasSize(32);
		now.addAndGet(601_000);
		assertThat(challenge().captchaId()).hasSize(32);
	}

	@Test
	void challengeRateLimitPerIp() {
		props.getRateLimit().setChallengePerMinute(3);
		for (int i = 0; i < 3; i++) {
			challenge();
		}
		CaptchaException e = (CaptchaException) catchCaptcha(this::challenge);
		assertThat(e.getCode()).isEqualTo(CaptchaCodes.RATE_LIMITED);
		assertThat(e.getData().get("retryAfter")).isNotNull();
	}

	@Test
	void globalTokenBucket() {
		props.getRateLimit().setGlobalChallengePerSecond(2);
		svc.challenge("login", "web", "000000", "pointer", "10.0.0.1");
		svc.challenge("login", "web", "000000", "pointer", "10.0.0.2");
		assertThat(code(() -> svc.challenge("login", "web", "000000", "pointer", "10.0.0.3"))).isEqualTo(CaptchaCodes.RATE_LIMITED);
		now.addAndGet(1000);
		assertThat(code(() -> svc.challenge("login", "web", "000000", "pointer", "10.0.0.3"))).isEqualTo(200);
	}

	@Test
	void keyboardModeChecksDurationStepPressesAndAnswer() {
		ChallengeVO c = keyboardChallenge();
		assertThat(c.inputMode()).isEqualTo("keyboard");
		assertThat(c.keyboardStep()).isEqualTo(4);
		assertThat(challenge().keyboardStep()).isNull();
		int ax = svc.peekAnswerX(c.captchaId());
		now.addAndGet(2000);
		long end = now.get();
		TokenVO t = svc.verify(new VerifyRequest(c.captchaId(), "keyboard", 320, end - 1500, end, null,
			new VerifyRequest.Answer((double) ax), presses(ax)), IP, UA);
		svc.consume(t.captchaToken(), "login", "web", "000000", IP, UA);

		// 正确落点 + 自报 3 次按键（与步长不符）→ 失败，且挑战作废
		ChallengeVO c2 = keyboardChallenge();
		int a2 = svc.peekAnswerX(c2.captchaId());
		now.addAndGet(2000);
		long e2 = now.get();
		assertThat(code(() -> svc.verify(new VerifyRequest(c2.captchaId(), "keyboard", 320, e2 - 1500, e2, null,
			new VerifyRequest.Answer((double) a2), 3), IP, UA))).isEqualTo(CaptchaCodes.FAILED);
		assertThat(code(() -> svc.verify(new VerifyRequest(c2.captchaId(), "keyboard", 320, e2 - 1500, e2, null,
			new VerifyRequest.Answer((double) a2), presses(a2)), IP, UA))).isEqualTo(CaptchaCodes.EXPIRED);

		// 按键次数 ±1 步可接受，±2 步拒绝
		ChallengeVO c3 = keyboardChallenge();
		int a3 = svc.peekAnswerX(c3.captchaId());
		now.addAndGet(2000);
		long e3 = now.get();
		assertThat(code(() -> svc.verify(new VerifyRequest(c3.captchaId(), "keyboard", 320, e3 - 1500, e3, null,
			new VerifyRequest.Answer((double) a3), presses(a3) + 1), IP, UA))).isEqualTo(200);
		ChallengeVO c4 = keyboardChallenge();
		int a4 = svc.peekAnswerX(c4.captchaId());
		now.addAndGet(2000);
		long e4 = now.get();
		assertThat(code(() -> svc.verify(new VerifyRequest(c4.captchaId(), "keyboard", 320, e4 - 1500, e4, null,
			new VerifyRequest.Answer((double) a4), presses(a4) + 2), IP, UA))).isEqualTo(CaptchaCodes.FAILED);
	}

	@Test
	void inputModeIsFixedAtChallenge() {
		// pointer 出题，verify 自报 keyboard（企图跳过轨迹校验）→ 失败
		ChallengeVO c = challenge();
		int ax = svc.peekAnswerX(c.captchaId());
		now.addAndGet(2000);
		long end = now.get();
		assertThat(code(() -> svc.verify(new VerifyRequest(c.captchaId(), "keyboard", 320, end - 1500, end, null,
			new VerifyRequest.Answer((double) ax), presses(ax)), IP, UA))).isEqualTo(CaptchaCodes.FAILED);
		// keyboard 出题，verify 按 pointer 提交 → 失败
		ChallengeVO k = keyboardChallenge();
		int kx = svc.peekAnswerX(k.captchaId());
		now.addAndGet(900);
		assertThat(code(() -> svc.verify(pointer(k.captchaId(), kx, 320, humanTrack(kx, 850), 850), IP, UA)))
			.isEqualTo(CaptchaCodes.FAILED);
	}

	@Test
	void smsBurstLimitRecoversAfterWindow() {
		for (int i = 0; i < 5; i++) {
			svc.hitSmsBurst("13800000030", IP);
		}
		CaptchaException e = (CaptchaException) catchCaptcha(() -> svc.hitSmsBurst("13800000030", IP));
		assertThat(e.getCode()).isEqualTo(CaptchaCodes.RATE_LIMITED);
		assertThat((Long) e.getData().get("retryAfter")).isBetween(1L, 60L);
		// 日配额不受影响
		assertThat(svc.smsPhoneCount("13800000030")).isZero();
		now.addAndGet(61_000);
		svc.hitSmsBurst("13800000030", IP);
	}

	@Test
	void smsQuotaCheckIsReadOnly() {
		for (int i = 0; i < 50; i++) {
			svc.checkSmsQuota("13800000009", IP);
		}
		assertThat(svc.smsPhoneCount("13800000009")).isZero();
		svc.takeSmsQuota("13800000009", IP);
		assertThat(svc.smsPhoneCount("13800000009")).isEqualTo(1);
	}

	@Test
	void smsQuotaConcurrentTakeNeverExceedsLimit() throws Exception {
		int threads = 20;
		java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newFixedThreadPool(threads);
		java.util.concurrent.CountDownLatch go = new java.util.concurrent.CountDownLatch(1);
		java.util.concurrent.atomic.AtomicInteger ok = new java.util.concurrent.atomic.AtomicInteger();
		java.util.concurrent.atomic.AtomicInteger limited = new java.util.concurrent.atomic.AtomicInteger();
		List<java.util.concurrent.Future<?>> fs = new ArrayList<>();
		for (int i = 0; i < threads; i++) {
			fs.add(pool.submit(() -> {
				go.await();
				try {
					svc.checkSmsQuota("13800000010", IP);
					svc.takeSmsQuota("13800000010", IP);
					ok.incrementAndGet();
				} catch (CaptchaException e) {
					assertThat(e.getCode()).isEqualTo(CaptchaCodes.RATE_LIMITED);
					assertThat(e.getData()).containsKey("retryAfter");
					limited.incrementAndGet();
				}
				return null;
			}));
		}
		go.countDown();
		for (java.util.concurrent.Future<?> f : fs) {
			f.get(10, java.util.concurrent.TimeUnit.SECONDS);
		}
		pool.shutdown();
		assertThat(ok.get()).isEqualTo(10);
		assertThat(limited.get()).isEqualTo(10);
		assertThat(svc.smsPhoneCount("13800000010")).isEqualTo(10);
	}

	@Test
	void failureWhileIpLockedByConcurrentRequestReturns40012() {
		// 模拟并发：本请求通过入口锁检查后、记失败前，另一请求刚把 IP 锁上
		CaptchaStore racy = new MemoryCaptchaStore(now::get) {
			@Override
			public String take(String key) {
				String v = super.take(key);
				if (key.contains(":challenge:")) {
					put(SliderCaptchaService.K_LOCK + IP, "keyboard", 600);
				}
				return v;
			}
		};
		SliderCaptchaService s2 = new SliderCaptchaService(racy, props,
			new PuzzleRenderer(props, new BackgroundLibrary(props)), CaptchaAuditSink.NOOP, now::get);
		ChallengeVO c = s2.challenge("login", "web", "000000", "pointer", IP);
		now.addAndGet(900);
		CaptchaException e = (CaptchaException) catchCaptcha(() ->
			s2.verify(pointer(c.captchaId(), 1, 320, humanTrack(1, 850), 850), IP, UA));
		assertThat(e.getCode()).isEqualTo(CaptchaCodes.RATE_LIMITED);
		assertThat((Long) e.getData().get("retryAfter")).isBetween(1L, 600L);
	}

	@Test
	void staleFailCounterAboveMaxLocksOnNextFailure() {
		// 旧计数（例如调低 verify-fail-max 前留下的）已超过上限：下一次失败即锁定并回 40012
		store.put(SliderCaptchaService.K_FAIL + "pointer:" + IP, "7", 600);
		ChallengeVO c = challenge();
		now.addAndGet(900);
		assertThat(code(() -> svc.verify(pointer(c.captchaId(), 1, 320, humanTrack(1, 850), 850), IP, UA)))
			.isEqualTo(CaptchaCodes.RATE_LIMITED);
	}

	@Test
	void pointerAndKeyboardFailuresCountedSeparately() {
		// pointer 失败 4 次 + keyboard 失败 2 次：均未达各自上限，不锁定
		for (int i = 0; i < 4; i++) {
			ChallengeVO c = challenge();
			now.addAndGet(900);
			assertThat(code(() -> svc.verify(pointer(c.captchaId(), 1, 320, humanTrack(1, 850), 850), IP, UA)))
				.isEqualTo(CaptchaCodes.FAILED);
		}
		for (int i = 0; i < 2; i++) {
			ChallengeVO c = keyboardChallenge();
			now.addAndGet(2000);
			long end = now.get();
			assertThat(code(() -> svc.verify(new VerifyRequest(c.captchaId(), "keyboard", 320, end - 1500, end, null,
				new VerifyRequest.Answer(1.0), 12), IP, UA))).isEqualTo(CaptchaCodes.FAILED);
		}
		assertThat(lockedAudit).isEmpty();
		// 第 5 次 pointer 失败才锁定，且直接 40012
		ChallengeVO c = challenge();
		now.addAndGet(900);
		assertThat(code(() -> svc.verify(pointer(c.captchaId(), 1, 320, humanTrack(1, 850), 850), IP, UA)))
			.isEqualTo(CaptchaCodes.RATE_LIMITED);
		assertThat(lockedAudit).containsExactly("203.0.113.7/pointer");
	}

	@Test
	void smsQuotaPerIpAndPhone() {
		props.getRateLimit().setSmsIpPerHour(3);
		props.getRateLimit().setSmsPhonePerDay(2);
		svc.assertSmsQuota("13800000001", IP);
		svc.assertSmsQuota("13800000001", IP);
		CaptchaException phone = (CaptchaException) catchCaptcha(() -> svc.assertSmsQuota("13800000001", IP));
		assertThat(phone.getCode()).isEqualTo(CaptchaCodes.RATE_LIMITED);
		assertThat(phone.getData()).containsKey("retryAfter");
		// 被拒的那次不计数：IP 计数仍为 2，换号还能发 1 次，之后 IP 达到 3 被拦
		assertThat(code(() -> svc.assertSmsQuota("13800000002", IP))).isEqualTo(200);
		assertThat(code(() -> svc.assertSmsQuota("13800000003", IP))).isEqualTo(CaptchaCodes.RATE_LIMITED);
		assertThat(code(() -> svc.assertSmsQuota("13800000002", "198.51.100.3"))).isEqualTo(200);
	}

	@Test
	void keyboardLimitsAreStricter() {
		for (int i = 0; i < 3; i++) {
			ChallengeVO c = keyboardChallenge();
			now.addAndGet(2000);
			long end = now.get();
			int expected = i < 2 ? CaptchaCodes.FAILED : CaptchaCodes.RATE_LIMITED;
			assertThat(code(() -> svc.verify(new VerifyRequest(c.captchaId(), "keyboard", 320, end - 1500, end, null,
				new VerifyRequest.Answer(1.0), 12), IP, UA))).isEqualTo(expected);
		}
		assertThat(lockedAudit).containsExactly("203.0.113.7/keyboard");
		assertThat(code(this::challenge)).isEqualTo(CaptchaCodes.RATE_LIMITED);
	}

	@Test
	void keyboardPerMinuteCap() {
		props.getRateLimit().setKeyboardFailMax(100);
		for (int i = 0; i < 6; i++) {
			code(() -> svc.verify(new VerifyRequest("0123456789abcdef0123456789abcdef", "keyboard", 320, 0L, 1L, null,
				new VerifyRequest.Answer(1.0), 5), IP, UA));
		}
		assertThat(code(() -> svc.verify(new VerifyRequest("0123456789abcdef0123456789abcdef", "keyboard", 320, 0L, 1L, null,
			new VerifyRequest.Answer(1.0), 5), IP, UA))).isEqualTo(CaptchaCodes.RATE_LIMITED);
	}

	@Test
	void tokenBindingIsStrict() {
		TokenVO a = pass();
		assertThat(code(() -> svc.consume(a.captchaToken(), "register", "web", "000000", IP, UA))).isEqualTo(CaptchaCodes.REQUIRED);
		// 不匹配也作废：换回正确场景同样失败
		assertThat(code(() -> svc.consume(a.captchaToken(), "login", "web", "000000", IP, UA))).isEqualTo(CaptchaCodes.REQUIRED);
		TokenVO b = pass();
		assertThat(code(() -> svc.consume(b.captchaToken(), "login", "web", "100001", IP, UA))).isEqualTo(CaptchaCodes.REQUIRED);
		TokenVO c = pass();
		assertThat(code(() -> svc.consume(c.captchaToken(), "login", "app", "000000", IP, UA))).isEqualTo(CaptchaCodes.REQUIRED);
	}

	@Test
	void optionalIpAndUaBinding() {
		props.getSlider().setBindIp(true);
		props.getSlider().setBindUa(true);
		TokenVO a = pass();
		assertThat(code(() -> svc.consume(a.captchaToken(), "login", "web", "000000", "198.51.100.9", UA))).isEqualTo(CaptchaCodes.REQUIRED);
		TokenVO b = pass();
		assertThat(code(() -> svc.consume(b.captchaToken(), "login", "web", "000000", IP, "other"))).isEqualTo(CaptchaCodes.REQUIRED);
		TokenVO c = pass();
		svc.consume(c.captchaToken(), "login", "web", "000000", IP, UA);
	}

	@Test
	void concurrentDoubleSpendSucceedsOnce() throws Exception {
		ChallengeVO ch = challenge();
		VerifyRequest req = good(ch);
		AtomicInteger verifyOk = new AtomicInteger();
		List<String> tokens = java.util.Collections.synchronizedList(new ArrayList<>());
		race(16, () -> {
			try {
				tokens.add(svc.verify(req, IP, UA).captchaToken());
				verifyOk.incrementAndGet();
			} catch (CaptchaException ignored) {
				// expected for losers
			}
		});
		assertThat(verifyOk.get()).isEqualTo(1);
		String token = tokens.get(0);
		AtomicInteger consumeOk = new AtomicInteger();
		race(16, () -> {
			try {
				svc.consume(token, "login", "web", "000000", IP, UA);
				consumeOk.incrementAndGet();
			} catch (CaptchaException ignored) {
				// expected for losers
			}
		});
		assertThat(consumeOk.get()).isEqualTo(1);
	}

	@Test
	void malformedInputIsFailureNotServerError() {
		ChallengeVO c = challenge();
		now.addAndGet(900);
		assertThat(code(() -> svc.verify(new VerifyRequest(c.captchaId(), null, null, null, null, null, null, null), IP, UA)))
			.isEqualTo(CaptchaCodes.FAILED);
		assertThat(code(() -> svc.verify(null, IP, UA))).isEqualTo(CaptchaCodes.EXPIRED);
		List<TrackPoint> huge = new ArrayList<>();
		for (int i = 0; i < 401; i++) {
			huge.add(new TrackPoint((double) i / 4, (double) (i % 3), (long) i * 2));
		}
		ChallengeVO c2 = challenge();
		int a2 = svc.peekAnswerX(c2.captchaId());
		now.addAndGet(900);
		assertThat(code(() -> svc.verify(pointer(c2.captchaId(), a2, 320, huge, 850), IP, UA))).isEqualTo(CaptchaCodes.FAILED);
	}

	private static void race(int threads, Runnable task) throws InterruptedException {
		ExecutorService pool = Executors.newFixedThreadPool(threads);
		CountDownLatch go = new CountDownLatch(1);
		for (int i = 0; i < threads; i++) {
			pool.submit(() -> {
				go.await();
				task.run();
				return null;
			});
		}
		go.countDown();
		pool.shutdown();
		assertThat(pool.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
	}

	private static RuntimeException catchCaptcha(Runnable r) {
		try {
			r.run();
		} catch (RuntimeException e) {
			return e;
		}
		throw new AssertionError("expected exception");
	}
}
