package com.mugsun.boot.security;

import cn.dev33.satoken.exception.SaTokenContextException;
import cn.dev33.satoken.stp.StpUtil;
import com.mugsun.boot.common.constant.AuthConstants;
import com.mugsun.boot.system.entity.SysUser;
import com.mugsun.boot.system.mapper.SysUserMapper;
import com.mugsun.boot.tenant.TenantContext;
import com.mugsun.core.tool.exception.ServiceException;
import jakarta.servlet.DispatcherType;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.servlet.HandlerInterceptor;

import java.util.Locale;
import java.util.Set;

/**
 * 首次登录强制改密：会话标记须改密时，除改密/登出/身份与 SM2 公钥外一律拒绝。
 * <p>标记在登录时写入；管理员重置口令会 kickout，下次登录重新读取库字段。
 */
public class ForceChangePasswordInterceptor implements HandlerInterceptor {

	/** 允许的精确路径（去掉 context-path 后的 servlet path） */
	private static final Set<String> ALLOWED = Set.of(
		"GET /auth/info",
		"GET /auth/sm2-public-key",
		"POST /auth/update-password",
		"POST /auth/logout",
		"GET /app/auth/me",
		"GET /app/auth/sm2-public-key",
		"POST /app/auth/logout"
	);

	private final SysUserMapper userMapper;

	public ForceChangePasswordInterceptor(SysUserMapper userMapper) {
		this.userMapper = userMapper;
	}

	@Override
	public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
		if (request.getDispatcherType() == DispatcherType.ASYNC) {
			return true;
		}
		boolean loggedIn;
		try {
			loggedIn = StpUtil.isLogin();
		} catch (SaTokenContextException e) {
			return true;
		}
		if (!loggedIn) {
			return true;
		}
		if (!mustChange()) {
			return true;
		}
		String key = request.getMethod().toUpperCase(Locale.ROOT) + " " + normalize(request);
		if (ALLOWED.contains(key)) {
			return true;
		}
		throw new ServiceException("请先修改初始口令后再使用系统");
	}

	private boolean mustChange() {
		Object raw = StpUtil.getSession().get(AuthConstants.MUST_CHANGE_PASSWORD_SESSION_KEY);
		if (raw instanceof Boolean b) {
			return b;
		}
		// 旧会话或未写入：回查库并回填会话，避免升级后已登录用户漏拦
		SysUser user = TenantContext.ignore(() -> userMapper.selectOneById(StpUtil.getLoginIdAsLong()));
		boolean flag = user != null && SecurityPolicyService.isMustChangePassword(user.getMustChangePassword());
		StpUtil.getSession().set(AuthConstants.MUST_CHANGE_PASSWORD_SESSION_KEY, flag);
		return flag;
	}

	/** 去掉 context-path，折叠多余斜杠，去掉末尾 / */
	static String normalize(HttpServletRequest request) {
		String uri = request.getRequestURI();
		String ctx = request.getContextPath();
		if (ctx != null && !ctx.isEmpty() && uri.startsWith(ctx)) {
			uri = uri.substring(ctx.length());
		}
		if (uri.isEmpty()) {
			uri = "/";
		}
		StringBuilder sb = new StringBuilder(uri.length());
		boolean slash = false;
		for (int i = 0; i < uri.length(); i++) {
			char c = uri.charAt(i);
			if (c == '/') {
				if (!slash) {
					sb.append('/');
				}
				slash = true;
			} else {
				sb.append(c);
				slash = false;
			}
		}
		String path = sb.toString();
		if (path.length() > 1 && path.endsWith("/")) {
			path = path.substring(0, path.length() - 1);
		}
		return path;
	}
}
