package com.mugsun.boot.security;

import com.mugsun.boot.security.entity.SysPasswordLog;
import com.mugsun.boot.security.mapper.SysPasswordLogMapper;
import com.mugsun.boot.system.service.ParamService;
import com.mugsun.core.tool.exception.ServiceException;
import com.mybatisflex.core.query.QueryWrapper;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 等保密码与登录安全策略：策略参数取自 sys_param（后台可改、即时生效），
 * 提供复杂度校验、历史密码防重、到期判定、失败锁定参数。
 */
@Service
public class SecurityPolicyService {

	private final ParamService paramService;
	private final PasswordEncoder passwordEncoder;
	private final SysPasswordLogMapper passwordLogMapper;

	public SecurityPolicyService(ParamService paramService, PasswordEncoder passwordEncoder,
								 SysPasswordLogMapper passwordLogMapper) {
		this.paramService = paramService;
		this.passwordEncoder = passwordEncoder;
		this.passwordLogMapper = passwordLogMapper;
	}

	// ---- 策略读取（带默认值，容错） ----
	private int intParam(String key, int def) {
		String v = paramService.getValue(key);
		if (v == null || v.isBlank()) {
			return def;
		}
		try {
			return Integer.parseInt(v.trim());
		} catch (NumberFormatException e) {
			return def;
		}
	}

	private boolean boolParam(String key, boolean def) {
		String v = paramService.getValue(key);
		return v == null || v.isBlank() ? def : "true".equalsIgnoreCase(v.trim());
	}

	public int getMinLength() {
		return intParam("security.password.min-length", 8);
	}

	public boolean isComplexityEnabled() {
		return boolParam("security.password.complexity", true);
	}

	/** 自助注册默认角色编码（sys_param 可配，置空则不挂任何角色） */
	public String getRegisterDefaultRoleCode() {
		String v = paramService.getValue("security.register.default-role-code");
		return v == null || v.isBlank()
			? com.mugsun.boot.common.constant.RoleConstants.USER
			: v.trim();
	}

	public int getHistoryCount() {
		return intParam("security.password.history-count", 3);
	}

	public int getExpireDays() {
		return intParam("security.password.expire-days", 0);
	}

	public int getLoginFailMax() {
		return intParam("security.login.fail-max", 5);
	}

	public int getLockMinutes() {
		return intParam("security.login.lock-minutes", 10);
	}

	public boolean isTwoFactorEnabled() {
		return boolParam("security.login.two-factor", false);
	}

	public String getTwoFactorChannel() {
		String v = paramService.getValue("security.login.two-factor-channel");
		return v == null || v.isBlank() ? "email" : v.trim();
	}

	/**
	 * 初始密码（种子 admin/新租户管理员/重置密码/导入用户共用）：
	 * 环境变量 MUGSUN_INIT_PASSWORD 注入，缺省 123456——生产部署必须改（等保：默认口令须换）。
	 */
	@org.springframework.beans.factory.annotation.Value("${mugsun.security.init-password:${MUGSUN_INIT_PASSWORD:123456}}")
	private String initPassword;

	public String getInitPassword() {
		return initPassword;
	}

	public boolean isWatermarkEnabled() {
		return boolParam("security.watermark.enabled", false);
	}

	// ---- 复杂度校验 ----
	public void validateComplexity(String raw) {
		if (raw == null || raw.length() < getMinLength()) {
			throw new ServiceException("密码至少 " + getMinLength() + " 位");
		}
		if (isComplexityEnabled()) {
			int categories = 0;
			if (raw.matches(".*[a-z].*")) {
				categories++;
			}
			if (raw.matches(".*[A-Z].*")) {
				categories++;
			}
			if (raw.matches(".*\\d.*")) {
				categories++;
			}
			if (raw.matches(".*[^a-zA-Z0-9].*")) {
				categories++;
			}
			if (categories < 3) {
				throw new ServiceException("密码需包含大写、小写、数字、特殊字符中至少 3 类");
			}
		}
	}

	// ---- 历史密码防重 ----
	public void checkHistory(Long userId, String rawNew) {
		int n = getHistoryCount();
		if (n <= 0) {
			return;
		}
		List<SysPasswordLog> logs = passwordLogMapper.selectListByQuery(
			QueryWrapper.create().eq("user_id", userId).orderBy("create_time", false).limit(n));
		for (SysPasswordLog log : logs) {
			if (passwordEncoder.matches(rawNew, log.getPassword())) {
				throw new ServiceException("新密码不能与最近 " + n + " 次使用的密码重复");
			}
		}
	}

	// ---- 记录历史密码 ----
	public void logPassword(Long userId, String encodedPassword) {
		SysPasswordLog log = new SysPasswordLog();
		log.setUserId(userId);
		log.setPassword(encodedPassword);
		log.setCreateTime(LocalDateTime.now());
		passwordLogMapper.insert(log);
	}

	// ---- 是否到期需强制改密 ----
	public boolean needChangePassword(Long userId) {
		int days = getExpireDays();
		if (days <= 0) {
			return false;
		}
		SysPasswordLog last = passwordLogMapper.selectOneByQuery(
			QueryWrapper.create().eq("user_id", userId).orderBy("create_time", false).limit(1));
		if (last == null || last.getCreateTime() == null) {
			return false;
		}
		return last.getCreateTime().plusDays(days).isBefore(LocalDateTime.now());
	}
}
