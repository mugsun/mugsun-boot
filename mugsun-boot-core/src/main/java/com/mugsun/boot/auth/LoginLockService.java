package com.mugsun.boot.auth;

import com.mugsun.boot.security.SecurityPolicyService;
import com.mugsun.core.tool.exception.ServiceException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.concurrent.TimeUnit;

/**
 * 登录锁定：按用户名维度累计失败次数，达阈值后锁定一段时间（防暴力破解与账号枚举）。
 * 失败计数与锁定标记落 Redis——重启不清零、多实例共享；阈值/时长取自安全策略参数（后台可改、即时生效）。
 */
@Service
public class LoginLockService {

	private static final String FAIL_KEY = "mugsun:login:fail:";
	private static final String LOCK_KEY = "mugsun:login:lock:";

	private final SecurityPolicyService securityPolicyService;
	private final StringRedisTemplate redis;

	public LoginLockService(SecurityPolicyService securityPolicyService, StringRedisTemplate redis) {
		this.securityPolicyService = securityPolicyService;
		this.redis = redis;
	}

	/** 校验未锁定，已锁定则抛异常；锁定标记 TTL 到期自动解锁 */
	public void assertNotLocked(String username) {
		Long ttl = redis.getExpire(LOCK_KEY + username, TimeUnit.MINUTES);
		if (ttl != null && ttl >= 0) {
			throw new ServiceException("账号已锁定，请 " + (ttl + 1) + " 分钟后再试");
		}
	}

	/** 组装锁键：租户维度 + 账号——多租户同名账号（如各租户 admin）独立计数，防跨租户连锁锁定与针对性锁死 */
	public String keyOf(String tenantId, String username) {
		return tenantId + ":" + username;
	}

	/** 记录一次失败，达阈值则锁定；计数窗口 = 锁定时长，滑动刷新 */
	public void recordFail(String username) {
		int lockMinutes = securityPolicyService.getLockMinutes();
		Long count = redis.opsForValue().increment(FAIL_KEY + username);
		redis.expire(FAIL_KEY + username, Duration.ofMinutes(lockMinutes));
		if (count != null && count >= securityPolicyService.getLoginFailMax()) {
			redis.opsForValue().set(LOCK_KEY + username, "1", Duration.ofMinutes(lockMinutes));
			redis.delete(FAIL_KEY + username);
		}
	}

	/** 登录成功后清除计数与锁定 */
	public void clear(String username) {
		redis.delete(FAIL_KEY + username);
		redis.delete(LOCK_KEY + username);
	}

	/** 账号是否处于锁定中（锁键存在即锁定；登录日志页「解锁」按钮显隐依据） */
	public boolean isLocked(String username) {
		return Boolean.TRUE.equals(redis.hasKey(LOCK_KEY + username));
	}
}
