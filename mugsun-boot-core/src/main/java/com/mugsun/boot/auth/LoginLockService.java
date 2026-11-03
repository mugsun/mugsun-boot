package com.mugsun.boot.auth;

import com.mugsun.boot.auth.lock.LoginLockStore;
import com.mugsun.boot.security.SecurityPolicyService;
import com.mugsun.core.tool.exception.ServiceException;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 登录锁定：按用户名维度累计失败次数，达阈值后锁定一段时间（防暴力破解与账号枚举）。
 * 失败计数与锁定标记默认落 Redis——重启不清零、多实例共享；开发态缺 Redis 时可降级为进程内存储。
 * 阈值/时长取自安全策略参数（后台可改、即时生效）。
 */
@Service
public class LoginLockService {

	@org.springframework.beans.factory.annotation.Value("${mugsun.demo.enabled:false}")
	private boolean demoMode;

	private static final int MAX_REASON = 200;
	private static final int MAX_MINUTES = 30 * 24 * 60;

	private final SecurityPolicyService securityPolicyService;
	private final LoginLockStore store;

	public LoginLockService(SecurityPolicyService securityPolicyService, LoginLockStore store) {
		this.securityPolicyService = securityPolicyService;
		this.store = store;
	}

	/** 校验未锁定。没有过期时间的锁一直有效，直到管理员解锁。 */
	public void assertNotLocked(String username) {
		if (!store.hasLock(username)) {
			return;
		}
		Long ttl = store.getLockTtlSeconds(username);
		String reason = reasonOf(username);
		boolean hasReason = reason != null && !reason.isBlank();
		if (ttl == null || ttl < 0) {
			throw new ServiceException(hasReason
				? "账号已锁定：" + reason + "。请联系管理员解锁"
				: "账号已锁定，请联系管理员解锁");
		}
		long minutes = Math.max(1, (ttl + 59) / 60);
		if (hasReason) {
			throw new ServiceException("账号已锁定：" + reason + "。请 " + minutes + " 分钟后再试，或联系管理员解锁");
		}
		throw new ServiceException("账号已锁定，请 " + minutes + " 分钟后再试");
	}

	/** 组装锁键：租户维度 + 账号——多租户同名账号（如各租户 admin）独立计数，防跨租户连锁锁定与针对性锁死 */
	public String keyOf(String tenantId, String username) {
		return tenantId + ":" + username;
	}

	/**
	 * 密码登录用的锁键。只读演示模式下演示账号密码公开，按「租户+账号+来源 IP」锁定：
	 * 一个 IP 输错只锁住它自己，不能把公开账号锁死给所有访客（安全复审 F1）。非 demo 同 {@link #keyOf}。
	 */
	public String keyForLogin(String tenantId, String username, String clientIp) {
		String key = keyOf(tenantId, username);
		return demoMode ? key + "@ip:" + (clientIp == null ? "-" : clientIp) : key;
	}

	/** 记录一次失败，达阈值则锁定；计数窗口 = 锁定时长，滑动刷新 */
	public void recordFail(String username) {
		int lockMinutes = securityPolicyService.getLockMinutes();
		Long count = store.incrementFail(username, Duration.ofMinutes(lockMinutes));
		if (count != null && count >= securityPolicyService.getLoginFailMax()) {
			Duration ttl = Duration.ofMinutes(lockMinutes);
			store.setLock(username, "fail", ttl);
			store.setMeta(username, "连续登录失败次数过多", ttl);
			store.addIndex(username);
			store.deleteFail(username);
		}
	}

	/**
	 * 管理员锁定。minutes 为空或小于等于 0 时一直锁定，直到解锁。
	 */
	public void manualLock(String lockKey, String reason, Integer minutes, String operator) {
		if (reason == null || reason.isBlank()) {
			throw new ServiceException("请填写锁定原因");
		}
		String text = reason.trim();
		if (text.length() > MAX_REASON) {
			throw new ServiceException("锁定原因请控制在 200 字以内");
		}
		String meta = text + "\u001f" + (operator == null ? "" : operator);
		if (minutes != null && minutes > 0) {
			if (minutes > MAX_MINUTES) {
				throw new ServiceException("锁定时长不能超过 30 天，留空则一直锁定到管理员解锁");
			}
			Duration ttl = Duration.ofMinutes(minutes);
			store.setLock(lockKey, "manual", ttl);
			store.setMeta(lockKey, meta, ttl);
		} else {
			store.setLock(lockKey, "manual", null);
			store.setMeta(lockKey, meta, null);
		}
		store.addIndex(lockKey);
	}

	/** 当前租户仍在锁定中的账号。过期项会从索引里清掉。 */
	public List<Map<String, Object>> list(String tenantId) {
		Set<String> members = store.indexMembers();
		List<Map<String, Object>> rows = new ArrayList<>();
		String prefix = tenantId + ":";
		for (String member : members) {
			if (member == null || !member.startsWith(prefix)) {
				continue;
			}
			if (!store.hasLock(member)) {
				store.removeIndex(member);
				store.deleteMeta(member);
				continue;
			}
			Long ttl = store.getLockTtlSeconds(member);
			String raw = store.getMeta(member);
			String reason = raw;
			String operator = "";
			if (raw != null && raw.contains("\u001f")) {
				int split = raw.indexOf('\u001f');
				reason = raw.substring(0, split);
				operator = raw.substring(split + 1);
			}
			Map<String, Object> row = new LinkedHashMap<>();
			row.put("lockKey", member);
			row.put("username", member.substring(prefix.length()));
			row.put("reason", reason == null ? "" : reason);
			row.put("operator", operator);
			boolean permanent = ttl == null || ttl < 0;
			row.put("permanent", permanent);
			row.put("remainMinutes", permanent ? null : Math.max(1, (ttl + 59) / 60));
			rows.add(row);
		}
		return rows;
	}

	/** 登录成功或管理员解锁后清除计数与锁定 */
	public void clear(String username) {
		store.deleteFail(username);
		store.deleteLock(username);
		store.deleteMeta(username);
		store.removeIndex(username);
	}

	private String reasonOf(String username) {
		String raw = store.getMeta(username);
		if (raw == null) {
			return null;
		}
		int split = raw.indexOf('\u001f');
		return split < 0 ? raw : raw.substring(0, split);
	}

	/** 账号是否处于锁定中（锁键存在即锁定；登录日志页「解锁」按钮显隐依据） */
	public boolean isLocked(String username) {
		return store.hasLock(username);
	}
}
