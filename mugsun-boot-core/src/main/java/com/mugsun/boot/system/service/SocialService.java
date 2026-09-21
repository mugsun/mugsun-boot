package com.mugsun.boot.system.service;

import cn.dev33.satoken.stp.StpUtil;
import cn.hutool.core.util.IdUtil;
import com.mugsun.boot.common.constant.TenantConstants;
import com.mugsun.boot.social.SocialAuthFactory;
import com.mugsun.boot.system.entity.SysUser;
import com.mugsun.boot.system.entity.SysUserOauth;
import com.mugsun.boot.system.mapper.SysUserMapper;
import com.mugsun.boot.system.mapper.SysUserOauthMapper;
import com.mugsun.core.tool.exception.ServiceException;
import com.mybatisflex.core.query.QueryWrapper;
import com.mugsun.boot.tenant.TenantContext;
import me.zhyd.oauth.model.AuthCallback;
import me.zhyd.oauth.model.AuthResponse;
import me.zhyd.oauth.model.AuthUser;
import me.zhyd.oauth.request.AuthRequest;
import me.zhyd.oauth.utils.AuthStateUtils;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 社交登录：以标准 OAuth 授权码流完成绑定/登录。openId 一律由服务端凭 code 向第三方换取
 * （AuthRequest.login，绝不信任客户端直传，杜绝越权）；state 经 Redis 缓存校验防 CSRF。
 * 端策略：C 端未绑定自动建号绑定；管理端未绑定拒绝、须先登录再绑定。
 */
@Service
public class SocialService {

	private final SysUserOauthMapper oauthMapper;
	private final SysUserMapper userMapper;
	private final PasswordEncoder passwordEncoder;
	private final SocialAuthFactory socialAuthFactory;
	private final com.mugsun.boot.tenant.TenantValidator tenantValidator;

	public SocialService(SysUserOauthMapper oauthMapper, SysUserMapper userMapper,
						 PasswordEncoder passwordEncoder, SocialAuthFactory socialAuthFactory,
						 com.mugsun.boot.tenant.TenantValidator tenantValidator) {
		this.oauthMapper = oauthMapper;
		this.userMapper = userMapper;
		this.passwordEncoder = passwordEncoder;
		this.socialAuthFactory = socialAuthFactory;
		this.tenantValidator = tenantValidator;
	}

	/** 生成第三方授权跳转地址（state 由 JustAuth 生成并缓存进 Redis） */
	public String renderAuthUrl(String source) {
		return socialAuthFactory.getAuthRequest(source).authorize(AuthStateUtils.createState());
	}

	/** 服务端用 code+state 向第三方换取用户信息（含 openId=uuid），state 由 JustAuth 内部经 Redis 校验 */
	private AuthUser resolveAuthUser(String source, String code, String state) {
		AuthRequest authRequest = socialAuthFactory.getAuthRequest(source);
		AuthCallback callback = new AuthCallback();
		callback.setCode(code);
		callback.setState(state);
		AuthResponse<AuthUser> response = authRequest.login(callback);
		if (!response.ok() || response.getData() == null) {
			throw new ServiceException("第三方授权失败：" + response.getMsg());
		}
		return response.getData();
	}

	/**
	 * 按来源+授权码登录：已绑定→登录；未绑定→autoRegister 为真则建号绑定登录，
	 * 否则拒绝（管理端禁自动开户）。返回 token。
	 */
	@Transactional(rollbackFor = Exception.class)
	public String loginByCode(String source, String code, String state, boolean autoRegister) {
		AuthUser authUser = resolveAuthUser(source, code, state);
		String openId = authUser.getUuid();
		SysUser user = TenantContext.ignore(() -> {
			SysUserOauth bind = oauthMapper.selectOneByQuery(QueryWrapper.create()
				.eq("source", source).eq("open_id", openId));
			if (bind != null) {
				return userMapper.selectOneById(bind.getUserId());
			}
			if (!autoRegister) {
				return null;
			}
			SysUser created = createSocialUser(source, authUser);
			userMapper.insert(created);
			insertOauth(created.getId(), source, authUser);
			return created;
		});
		if (user == null) {
			throw new ServiceException("该第三方账号尚未绑定平台用户，请先登录并绑定后再使用第三方登录");
		}
		// 与账密登录同口径：停用账号/停用过期租户禁止社交登录
		if (user.getStatus() == null || user.getStatus() != 1) {
			throw new ServiceException("账号已停用，请联系管理员");
		}
		String tenantInvalid = tenantValidator.validate(user.getTenantId());
		if (tenantInvalid != null) {
			throw new ServiceException(tenantInvalid);
		}
		StpUtil.login(user.getId());
		StpUtil.getSession().set(TenantContext.TENANT_SESSION_KEY, user.getTenantId());
		return StpUtil.getTokenValue();
	}

	/** 当前登录用户绑定第三方账号：服务端换 openId + 双查重（本人幂等 / 该 openId 已被他人绑定则拒绝，防劫持） */
	@Transactional(rollbackFor = Exception.class)
	public void bindByCode(Long userId, String source, String code, String state) {
		AuthUser authUser = resolveAuthUser(source, code, state);
		String openId = authUser.getUuid();
		TenantContext.ignore(() -> {
			SysUserOauth exist = oauthMapper.selectOneByQuery(QueryWrapper.create()
				.eq("source", source).eq("open_id", openId));
			if (exist != null) {
				if (exist.getUserId().equals(userId)) {
					return null;
				}
				throw new ServiceException("该第三方账号已绑定其他用户");
			}
			insertOauth(userId, source, authUser);
			return null;
		});
	}

	/** 当前登录用户解绑某来源第三方账号 */
	public void unbind(Long userId, String source) {
		TenantContext.ignore(() -> {
			SysUserOauth bind = oauthMapper.selectOneByQuery(QueryWrapper.create()
				.eq("source", source).eq("user_id", userId));
			if (bind != null) {
				oauthMapper.deleteById(bind.getId());
			}
			return null;
		});
	}

	private void insertOauth(Long userId, String source, AuthUser authUser) {
		SysUserOauth oauth = new SysUserOauth();
		oauth.setUserId(userId);
		oauth.setSource(source);
		oauth.setOpenId(authUser.getUuid());
		oauthMapper.insert(oauth);
	}

	/** 未绑定时自助建号：用户名 source_openid 前缀，随机密码，默认租户 */
	private SysUser createSocialUser(String source, AuthUser authUser) {
		String openId = authUser.getUuid();
		String suffix = openId.length() > 40 ? openId.substring(0, 40) : openId;
		SysUser user = new SysUser();
		user.setUsername(source + "_" + suffix);
		user.setNickname(authUser.getNickname() != null ? authUser.getNickname() : source + "用户" + suffix);
		user.setPassword(passwordEncoder.encode(IdUtil.fastSimpleUUID()));
		user.setStatus(1);
		user.setTenantId(TenantConstants.DEFAULT_TENANT_ID);
		return user;
	}
}
