package com.mugsun.boot.system.service;

import cn.dev33.satoken.stp.StpUtil;
import cn.hutool.core.util.IdUtil;
import com.mugsun.boot.common.constant.TenantConstants;
import com.mugsun.boot.social.MugsunAuthStateRedisCache;
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

import java.util.Locale;

/**
 * 社交登录：以标准 OAuth 授权码流完成绑定/登录。openId 一律由服务端凭 code 向第三方换取
 * （AuthRequest.login，绝不信任客户端直传，杜绝越权）；state 经 Redis 缓存校验防 CSRF。
 * 每个 state 发出时记下用途（登录，或某个用户对某个来源的绑定），回调时取出即删：
 * 过期、重复使用、拿登录的 state 去绑定、拿别人发起的绑定 state 来绑定，一律拒绝。
 * 端策略：C 端未绑定自动建号绑定；管理端未绑定拒绝、须先登录再绑定。
 */
@Service
public class SocialService {

	private final SysUserOauthMapper oauthMapper;
	private final SysUserMapper userMapper;
	private final PasswordEncoder passwordEncoder;
	private final SocialAuthFactory socialAuthFactory;
	private final com.mugsun.boot.tenant.TenantValidator tenantValidator;
	private final MugsunAuthStateRedisCache stateCache;

	public SocialService(SysUserOauthMapper oauthMapper, SysUserMapper userMapper,
						 PasswordEncoder passwordEncoder, SocialAuthFactory socialAuthFactory,
						 com.mugsun.boot.tenant.TenantValidator tenantValidator,
						 MugsunAuthStateRedisCache stateCache) {
		this.oauthMapper = oauthMapper;
		this.userMapper = userMapper;
		this.passwordEncoder = passwordEncoder;
		this.socialAuthFactory = socialAuthFactory;
		this.tenantValidator = tenantValidator;
		this.stateCache = stateCache;
	}

	/** 登录用的授权跳转地址（state 由 JustAuth 生成并缓存进 Redis）：这个 state 只能用来登录 */
	public String renderAuthUrl(String source) {
		return render(source, loginPurpose(source));
	}

	/** 绑定用的授权跳转地址：state 记下发起绑定的用户，回调时只有这个用户能拿它绑定 */
	public String renderBindUrl(String source, Long userId) {
		return render(source, bindPurpose(source, userId));
	}

	private String render(String source, String purpose) {
		AuthRequest authRequest = socialAuthFactory.getAuthRequest(source);
		String state = AuthStateUtils.createState();
		String url = authRequest.authorize(state);
		stateCache.markPurpose(state, purpose);
		return url;
	}

	private static String loginPurpose(String source) {
		return "login:" + normalizeSource(source);
	}

	private static String bindPurpose(String source, Long userId) {
		return "bind:" + normalizeSource(source) + ":" + userId;
	}

	private static String normalizeSource(String source) {
		return source == null ? "" : source.toLowerCase(Locale.ROOT);
	}

	/**
	 * 服务端用 code+state 向第三方换取用户信息（含 openId=uuid）。换 code 之前先取出并作废 state 的用途：
	 * 过期、用过、用途不符都直接拒绝；换完无论成败都删掉 JustAuth 的 state，同一个 state 只能用一次。
	 */
	private AuthUser resolveAuthUser(String source, String code, String state, String expectedPurpose) {
		String purpose = state == null || state.isBlank() ? null : stateCache.takePurpose(state);
		if (purpose == null) {
			throw new ServiceException("授权已过期或已经用过。请回到原来的页面重新发起授权");
		}
		if (!purpose.equals(expectedPurpose)) {
			stateCache.remove(state);
			throw new ServiceException(expectedPurpose.startsWith("bind:")
				? "这次授权不是你在个人中心发起的绑定，已拒绝。要绑定请到个人中心重新点「绑定」"
				: "这次授权不是从登录页发起的，不能用来登录。请回到登录页重新发起第三方登录");
		}
		AuthRequest authRequest = socialAuthFactory.getAuthRequest(source);
		AuthCallback callback = new AuthCallback();
		callback.setCode(code);
		callback.setState(state);
		AuthResponse<AuthUser> response;
		try {
			response = authRequest.login(callback);
		} finally {
			stateCache.remove(state);
		}
		if (!response.ok() || response.getData() == null) {
			throw new ServiceException("第三方授权失败。请回到登录页，重新发起授权后再试");
		}
		return response.getData();
	}

	/**
	 * 按来源+授权码登录：已绑定→登录；未绑定→autoRegister 为真则建号绑定登录，
	 * 否则拒绝（管理端禁自动开户）。返回 token。
	 */
	@Transactional(rollbackFor = Exception.class)
	public String loginByCode(String source, String code, String state, boolean autoRegister) {
		AuthUser authUser = resolveAuthUser(source, code, state, loginPurpose(source));
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

	/**
	 * 当前登录用户绑定第三方账号：state 必须是本人从 bind-render 发起的；服务端换 openId + 查重
	 * （本人幂等 / 该 openId 已被他人绑定则拒绝，防劫持 / 本人已绑过这个来源的另一个账号则拒绝，先解绑再换）
	 */
	@Transactional(rollbackFor = Exception.class)
	public void bindByCode(Long userId, String source, String code, String state) {
		AuthUser authUser = resolveAuthUser(source, code, state, bindPurpose(source, userId));
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
			long mine = oauthMapper.selectCountByQuery(QueryWrapper.create()
				.eq("source", source).eq("user_id", userId));
			if (mine > 0) {
				throw new ServiceException("你已经绑定了一个这个来源的账号。要换绑，请先在个人中心解绑");
			}
			insertOauth(userId, source, authUser);
			return null;
		});
	}

	/** 当前登录用户解绑某来源的第三方账号：这个来源的绑定全部删掉（旧数据里可能不止一条） */
	public void unbind(Long userId, String source) {
		TenantContext.ignore(() -> {
			oauthMapper.deleteByQuery(QueryWrapper.create()
				.eq("source", source).eq("user_id", userId));
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
		user.setPassword(passwordEncoder.encode(IdUtil.simpleUUID()));
		user.setMustChangePassword(0);
		user.setStatus(1);
		user.setTenantId(TenantConstants.DEFAULT_TENANT_ID);
		return user;
	}
}
