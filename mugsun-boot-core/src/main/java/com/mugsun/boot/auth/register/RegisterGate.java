package com.mugsun.boot.auth.register;

import com.mugsun.boot.common.env.DeployProfiles;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.env.Environment;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Locale;

/**
 * 自助注册闸门：解析注册模式，并按 IP、账号、手机号做固定窗口限流（Redis 计数，与找回密码、登录锁同一套做法）。
 * <p>配置：
 * <ul>
 *   <li>{@code mugsun.auth.register.mode}=OFF|OPEN|APPROVAL（主配置）</li>
 *   <li>{@code mugsun.auth.register.enabled}=true|false（简写，分别等于 OPEN、OFF）</li>
 *   <li>都不设：生产 OFF，dev/test/local OPEN（判定见 {@link DeployProfiles}）</li>
 * </ul>
 * 两者都设且含义不同、或设成 APPROVAL、或值无法识别，构造时抛异常，应用拒绝启动。
 */
@Component
public class RegisterGate {

	private static final Logger log = LoggerFactory.getLogger(RegisterGate.class);
	static final String KEY_PREFIX = "mugsun:register:rl:";

	private final RegisterMode mode;
	private final StringRedisTemplate redis;
	private final long perIp;
	private final long perAccount;
	private final Duration window;

	public RegisterGate(Environment env, StringRedisTemplate redis) {
		this.mode = resolve(env);
		this.redis = redis;
		this.perIp = env.getProperty("mugsun.auth.register.rate-limit.per-ip", Long.class, 10L);
		this.perAccount = env.getProperty("mugsun.auth.register.rate-limit.per-account", Long.class, 5L);
		this.window = Duration.ofSeconds(env.getProperty("mugsun.auth.register.rate-limit.window-seconds", Long.class, 3600L));
		log.info("自助注册模式：{}", mode);
	}

	public RegisterMode mode() {
		return mode;
	}

	public boolean isOpen() {
		return mode == RegisterMode.OPEN;
	}

	/** 解析注册模式；冲突或未实现的模式抛 {@link IllegalStateException}（启动失败） */
	public static RegisterMode resolve(Environment env) {
		String rawMode = env.getProperty("mugsun.auth.register.mode");
		String rawEnabled = env.getProperty("mugsun.auth.register.enabled");
		RegisterMode fromMode = null;
		if (rawMode != null && !rawMode.isBlank()) {
			try {
				fromMode = RegisterMode.valueOf(rawMode.trim().toUpperCase(Locale.ROOT));
			} catch (IllegalArgumentException e) {
				throw new IllegalStateException("mugsun.auth.register.mode 只能是 OFF、OPEN 或 APPROVAL，当前值无法识别");
			}
		}
		RegisterMode fromEnabled = null;
		if (rawEnabled != null && !rawEnabled.isBlank()) {
			String v = rawEnabled.trim().toLowerCase(Locale.ROOT);
			if ("true".equals(v)) {
				fromEnabled = RegisterMode.OPEN;
			} else if ("false".equals(v)) {
				fromEnabled = RegisterMode.OFF;
			} else {
				throw new IllegalStateException("mugsun.auth.register.enabled 只能是 true 或 false");
			}
		}
		if (fromMode != null && fromEnabled != null && fromMode != fromEnabled) {
			throw new IllegalStateException("mugsun.auth.register.mode 与 mugsun.auth.register.enabled 含义冲突，"
				+ "请只保留一个（enabled=true 等于 mode=OPEN，enabled=false 等于 mode=OFF）");
		}
		RegisterMode m = fromMode != null ? fromMode : fromEnabled;
		if (m == null) {
			m = DeployProfiles.isNonProd(env) ? RegisterMode.OPEN : RegisterMode.OFF;
		}
		if (m == RegisterMode.APPROVAL) {
			throw new IllegalStateException("mugsun.auth.register.mode=APPROVAL：注册审核尚未实现，请改为 OFF 或 OPEN");
		}
		return m;
	}

	/** 计数并检查：任一维度超限抛 {@link RegisterRateLimitException}（HTTP 429） */
	public void checkRate(String ip, String username, String phone) {
		hit("ip:" + (ip == null ? "unknown" : ip), perIp);
		if (username != null && !username.isBlank()) {
			hit("user:" + username.trim().toLowerCase(Locale.ROOT), perAccount);
		}
		if (phone != null && !phone.isBlank()) {
			hit("phone:" + phone.trim(), perAccount);
		}
	}

	private void hit(String dim, long limit) {
		String key = KEY_PREFIX + dim;
		Long n;
		try {
			n = redis.opsForValue().increment(key);
			if (n != null && n == 1L) {
				redis.expire(key, window);
			}
		} catch (RuntimeException e) {
			// Redis 不可用时不放行：注册是匿名写入口，宁可失败
			throw new RegisterRateLimitException("注册服务暂时不可用。请稍后再试");
		}
		if (n != null && n > limit) {
			throw new RegisterRateLimitException("注册太频繁了。请过一段时间再试");
		}
	}
}
