package com.mugsun.boot.auth;

import cn.dev33.satoken.stp.StpUtil;
import com.mugsun.boot.common.constant.AuthConstants;
import com.mugsun.boot.mail.MailService;
import com.mugsun.boot.security.SecurityPolicyService;
import com.mugsun.boot.system.entity.SysUser;
import com.mugsun.boot.system.mapper.SysUserMapper;
import com.mugsun.boot.tenant.TenantContext;
import com.mugsun.core.tool.exception.ServiceException;
import com.mybatisflex.core.query.QueryWrapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.security.SecureRandom;

/**
 * 忘记密码（邮件验证码重置）：账号定位用户→绑定邮箱下发 6 位码（Redis 5min TTL，校验即焚）→
 * 凭码重置（复杂度/历史防重复用 SecurityPolicyService，重置即全端下线）。
 * 防爆破：发码走图形验证码+同账号 60s 节流；错码累计 5 次烧毁验证码。
 * 防枚举：账号不存在/未绑邮箱/通道未配置/下发失败对外一律返回同一成功话术，仅日志留痕（不对外暴露通道状态）。
 */
@Service
public class ForgetPasswordService {

	private static final Logger log = LoggerFactory.getLogger(ForgetPasswordService.class);

	private final StringRedisTemplate redis;
	private final SysUserMapper userMapper;
	private final MailService mailService;
	private final SecurityPolicyService securityPolicyService;
	private final PasswordEncoder passwordEncoder;
	private final ForgetMailSender mailSender;
	private final SecureRandom random = new SecureRandom();

	public ForgetPasswordService(StringRedisTemplate redis, SysUserMapper userMapper,
								 MailService mailService, SecurityPolicyService securityPolicyService,
								 PasswordEncoder passwordEncoder, ForgetMailSender mailSender) {
		this.redis = redis;
		this.userMapper = userMapper;
		this.mailService = mailService;
		this.securityPolicyService = securityPolicyService;
		this.passwordEncoder = passwordEncoder;
		this.mailSender = mailSender;
	}

	/** 发送重置验证码：账号不存在/未绑邮箱/通道未配置/下发失败均静默成功（防枚举、不泄露通道状态），仅日志留痕 */
	public void sendCode(String username, String tenantId) {
		String throttleKey = AuthConstants.FORGET_THROTTLE_PREFIX + tenantId + ":" + username;
		Boolean first = redis.opsForValue().setIfAbsent(throttleKey, "1",
			Duration.ofSeconds(AuthConstants.FORGET_THROTTLE_SECONDS));
		if (!Boolean.TRUE.equals(first)) {
			throw new ServiceException("发送过于频繁，请 60 秒后再试");
		}
		SysUser user = findUser(username, tenantId);
		if (user == null || user.getEmail() == null || user.getEmail().isBlank()) {
			log.info("忘记密码发码：账号不存在或未绑定邮箱 tenant={} username={}", tenantId, username);
			return;
		}
		if (!mailService.isConfigured()) {
			log.warn("忘记密码发码：邮件通道未配置，未下发 tenant={} username={}", tenantId, username);
			return;
		}
		String code = String.valueOf(100000 + random.nextInt(900000));
		String codeKey = codeKey(tenantId, username);
		redis.opsForValue().set(codeKey, code, Duration.ofSeconds(AuthConstants.FORGET_CODE_EXPIRE_SECONDS));
		// 异步下发：响应时间不随账号是否存在/邮件通道耗时变化（防计时侧信道枚举账号）
		mailSender.send(user.getEmail(), code, codeKey, tenantId, username);
	}

	/** 凭验证码重置密码：复杂度/历史防重 + BCrypt 落库 + 密码历史记录 + 全端下线（旧会话一律作废） */
	public void resetPassword(String username, String tenantId, String code, String rawPassword) {
		// 复杂度校验前置：不消耗验证码（复杂度失败可修正后原码重试）
		securityPolicyService.validateComplexity(rawPassword);
		String codeKey = codeKey(tenantId, username);
		String attemptKey = AuthConstants.FORGET_ATTEMPT_PREFIX + tenantId + ":" + username;
		String cached = redis.opsForValue().get(codeKey);
		if (cached == null) {
			throw new ServiceException("验证码已过期，请重新获取");
		}
		if (!cached.equals(code)) {
			Long attempts = redis.opsForValue().increment(attemptKey);
			if (attempts != null && attempts == 1L) {
				redis.expire(attemptKey, Duration.ofSeconds(AuthConstants.FORGET_CODE_EXPIRE_SECONDS));
			}
			if (attempts != null && attempts >= AuthConstants.FORGET_MAX_ATTEMPTS) {
				redis.delete(codeKey);
				redis.delete(attemptKey);
				throw new ServiceException("验证码错误次数过多，请重新获取");
			}
			throw new ServiceException("验证码错误。请核对后再填");
		}
		redis.delete(codeKey);
		redis.delete(attemptKey);
		SysUser user = findUser(username, tenantId);
		if (user == null) {
			throw new ServiceException("账号不存在或已删除");
		}
		securityPolicyService.checkHistory(user.getId(), rawPassword);
		String encoded = passwordEncoder.encode(rawPassword);
		user.setPassword(encoded);
		TenantContext.ignore(() -> userMapper.update(user));
		securityPolicyService.logPassword(user.getId(), encoded);
		// 重置即全端下线（与改密同口径：旧密码签发的会话一律作废）
		StpUtil.kickout(user.getId());
		log.info("忘记密码重置成功 tenant={} username={}", tenantId, username);
	}

	private SysUser findUser(String username, String tenantId) {
		return TenantContext.ignore(() -> userMapper.selectOneByQuery(
			QueryWrapper.create().eq("tenant_id", tenantId).eq("username", username)));
	}

	private String codeKey(String tenantId, String username) {
		return AuthConstants.FORGET_CODE_PREFIX + tenantId + ":" + username;
	}
}
