package com.mugsun.boot.auth;

import cn.dev33.satoken.stp.StpUtil;
import com.mugsun.boot.client.entity.SysClient;
import com.mugsun.boot.common.constant.MonitorConstants;
import com.mugsun.boot.common.constant.TenantConstants;
import com.mugsun.boot.log.entity.SysLoginLog;
import com.mugsun.boot.system.entity.SysUser;
import com.mugsun.boot.log.LoginLogService;
import com.mugsun.boot.system.mapper.SysUserMapper;
import com.mugsun.boot.tenant.TenantContext;
import com.mugsun.boot.websocket.WsMessageSender;
import com.mugsun.core.tool.exception.ServiceException;
import com.mybatisflex.core.query.QueryWrapper;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;

/**
 * 登录内核：账号口令校验、锁号、租户闸门、双因子、发 token。
 * PC {@code /auth/login} 与 App {@code /app/auth/login} 共用，通道只负责各自人机校验。
 */
@Service
public class AuthLoginService {

	private final SysUserMapper userMapper;
	private final PasswordEncoder passwordEncoder;
	private final LoginLockService loginLockService;
	private final LoginLogService loginLogService;
	private final TwoFactorService twoFactorService;
	private final com.mugsun.boot.security.SecurityPolicyService securityPolicyService;
	private final com.mugsun.boot.tenant.TenantValidator tenantValidator;
	private final com.mugsun.boot.common.crypto.GmCryptoConfig gmCryptoConfig;
	private final WsMessageSender wsMessageSender;
	private final IpRegionService ipRegionService;

	public AuthLoginService(SysUserMapper userMapper, PasswordEncoder passwordEncoder,
							LoginLockService loginLockService, LoginLogService loginLogService,
							TwoFactorService twoFactorService,
							com.mugsun.boot.security.SecurityPolicyService securityPolicyService,
							com.mugsun.boot.tenant.TenantValidator tenantValidator,
							com.mugsun.boot.common.crypto.GmCryptoConfig gmCryptoConfig,
							WsMessageSender wsMessageSender,
							IpRegionService ipRegionService) {
		this.userMapper = userMapper;
		this.passwordEncoder = passwordEncoder;
		this.loginLockService = loginLockService;
		this.loginLogService = loginLogService;
		this.twoFactorService = twoFactorService;
		this.securityPolicyService = securityPolicyService;
		this.tenantValidator = tenantValidator;
		this.gmCryptoConfig = gmCryptoConfig;
		this.wsMessageSender = wsMessageSender;
		this.ipRegionService = ipRegionService;
	}

	/**
	 * 账号+SM2 密文密码登录（人机校验已在调用方完成）。
	 */
	public Map<String, Object> loginByPassword(String username, String passwordCipher, String tenantIdRaw,
												 SysClient client, HttpServletRequest request) {
		if (username == null || username.isBlank()) {
			throw new ServiceException("账号或密码错误");
		}
		String tenantId = (tenantIdRaw == null || tenantIdRaw.isBlank())
			? TenantConstants.DEFAULT_TENANT_ID : tenantIdRaw;
		String lockKey = loginLockService.keyOf(tenantId, username);
		loginLockService.assertNotLocked(lockKey);
		SysUser user = TenantContext.ignore(() ->
			userMapper.selectOneByQuery(QueryWrapper.create().eq("tenant_id", tenantId).eq("username", username)));
		String rawPassword;
		try {
			rawPassword = decodePassword(passwordCipher);
		} catch (Exception e) {
			rawPassword = null;
		}
		if (user == null || rawPassword == null || !passwordEncoder.matches(rawPassword, user.getPassword())) {
			loginLockService.recordFail(lockKey);
			saveLoginLog(username, request, client.getClientId(), tenantId, 0, "账号或密码错误");
			throw new ServiceException("账号或密码错误");
		}
		String tenantInvalid = tenantValidator.validate(tenantId);
		if (tenantInvalid != null) {
			saveLoginLog(username, request, client.getClientId(), tenantId, 0, tenantInvalid);
			throw new ServiceException(tenantInvalid);
		}
		assertUserLoginable(user, username, request, client.getClientId());
		loginLockService.clear(lockKey);
		if (twoFactorService.isEnabled()) {
			String[] challenge = twoFactorService.challenge(user.getId(), twoFactorContact(user));
			saveLoginLog(username, request, client.getClientId(), user.getTenantId(), 1, "登录待二次验证");
			Map<String, Object> resp = new HashMap<>();
			resp.put("twoFactorRequired", true);
			resp.put("twoFactorToken", challenge[0]);
			if (challenge[1] != null) {
				resp.put("twoFactorCode", challenge[1]);
			}
			return resp;
		}
		return issueToken(user, client, request, "登录成功");
	}

	/** 双因子二次校验通过后发 token */
	public Map<String, Object> completeTwoFactor(String twoFactorToken, String code, SysClient client,
												 HttpServletRequest request) {
		Long userId = twoFactorService.verify(twoFactorToken, code);
		SysUser user = TenantContext.ignore(() -> userMapper.selectOneById(userId));
		assertUserLoginable(user, user.getUsername(), request, client.getClientId());
		return issueToken(user, client, request, "双因子登录成功");
	}

	public Map<String, Object> issueToken(SysUser user, SysClient client, HttpServletRequest request, String logMsg) {
		String ip = request.getRemoteAddr();
		StpUtil.login(user.getId(), new cn.dev33.satoken.stp.parameter.SaLoginParameter()
			.setDeviceType(client.getClientId())
			.setTimeout(client.getTokenTimeout() == null ? 2592000 : client.getTokenTimeout())
			.setTerminalExtra(MonitorConstants.TERMINAL_EXTRA_IP, ip)
			.setTerminalExtra(MonitorConstants.TERMINAL_EXTRA_UA, truncateUa(request)));
		StpUtil.getSession().set(TenantContext.TENANT_SESSION_KEY, user.getTenantId());
		enforceMaxOnline(user.getId(), client.getMaxOnline());
		saveLoginLog(user.getUsername(), request, client.getClientId(), user.getTenantId(), 1, logMsg);
		return Map.of("token", StpUtil.getTokenValue());
	}

	public void logout() {
		String tokenValue = StpUtil.getTokenValue();
		StpUtil.logout();
		if (tokenValue != null && !tokenValue.isBlank()) {
			wsMessageSender.closeUser(null, tokenValue, "已登出");
		}
	}

	public String decodePassword(String raw) {
		if (raw == null || raw.isBlank() || !gmCryptoConfig.isGmEnabled()) {
			return raw;
		}
		return com.mugsun.boot.common.crypto.Sm2Util.decrypt(raw);
	}

	public String decodePasswordNormalized(String raw) {
		try {
			return decodePassword(raw);
		} catch (Exception e) {
			throw new ServiceException("请求参数无效");
		}
	}

	public void assertUserLoginable(SysUser user, String username, HttpServletRequest request, String clientId) {
		if (user.getStatus() == null || user.getStatus() != 1) {
			saveLoginLog(username, request, clientId, user.getTenantId(), 0, "账号已停用");
			throw new ServiceException("账号已停用，请联系管理员");
		}
	}

	public void saveLoginLog(String username, HttpServletRequest request, String device, String tenantId,
							 int status, String msg) {
		SysLoginLog log = new SysLoginLog();
		log.setUsername(username);
		log.setIp(request.getRemoteAddr());
		String ua = truncateUa(request);
		log.setUserAgent(ua);
		if (ua != null && !ua.isBlank()) {
			cn.hutool.http.useragent.UserAgent agent = cn.hutool.http.useragent.UserAgentUtil.parse(ua);
			log.setBrowser(agent.getBrowser() == null ? null : agent.getBrowser().getName());
			log.setOs(agent.getOs() == null ? null : agent.getOs().getName());
		}
		log.setLoginLocation(ipRegionService.resolve(log.getIp()));
		log.setDevice(device);
		log.setTenantId(tenantId);
		log.setStatus(status);
		log.setMsg(msg);
		log.setLoginTime(LocalDateTime.now());
		loginLogService.record(log);
	}

	public String truncateUa(HttpServletRequest request) {
		String ua = request.getHeader("User-Agent");
		return ua != null && ua.length() > MonitorConstants.UA_MAX_LEN
			? ua.substring(0, MonitorConstants.UA_MAX_LEN) : ua;
	}

	private void enforceMaxOnline(long userId, Integer maxOnline) {
		if (maxOnline == null || maxOnline <= 0) {
			return;
		}
		java.util.List<String> tokens = StpUtil.getTokenValueListByLoginId(userId);
		int excess = tokens.size() - maxOnline;
		for (int i = 0; i < excess; i++) {
			StpUtil.kickoutByTokenValue(tokens.get(i));
		}
	}

	private String twoFactorContact(SysUser user) {
		String channel = securityPolicyService.getTwoFactorChannel();
		if ("sms".equals(channel)) {
			return user.getPhone();
		}
		return user.getEmail();
	}
}
