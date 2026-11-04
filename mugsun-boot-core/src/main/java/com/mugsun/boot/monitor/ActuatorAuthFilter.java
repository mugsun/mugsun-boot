package com.mugsun.boot.monitor;

import cn.dev33.satoken.exception.NotLoginException;
import cn.dev33.satoken.exception.NotPermissionException;
import cn.dev33.satoken.stp.StpUtil;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mugsun.boot.common.constant.MonitorConstants;
import com.mugsun.core.tool.api.R;
import com.mugsun.core.tool.api.ResultCode;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.annotation.Order;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.UrlPathHelper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * actuator 端点鉴权：prometheus/metrics 等需登录 + {@code sys:monitor:list} 权限码；
 * health/info 保持公开（G1 契约：部署探活不鉴权）。
 * <p>路径先经 {@link UrlPathHelper#getLookupPathForRequest} 归一（剥离 context-path、URL 解码、
 * 去掉分号矩阵/jsessionid，并跟随容器已归一的 servletPath），再段感知匹配，与 Spring 映射一致，
 * 避免按原始 URI 判断时漏拦能映射到受保护端点的变体。
 */
@Component
@Order(-80)
public class ActuatorAuthFilter extends OncePerRequestFilter {

	/** 与 Spring MVC 默认查找路径策略一致（解码 + 去分号） */
	private static final UrlPathHelper PATH_HELPER = UrlPathHelper.defaultInstance;

	private static final String ACTUATOR_ROOT = "/actuator";

	private final ObjectMapper objectMapper;

	public ActuatorAuthFilter(ObjectMapper objectMapper) {
		this.objectMapper = objectMapper;
	}

	@Override
	protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
		throws ServletException, IOException {
		if (!isGuarded(request)) {
			chain.doFilter(request, response);
			return;
		}
		try {
			StpUtil.checkLogin();
		} catch (NotLoginException e) {
			write(response, HttpServletResponse.SC_UNAUTHORIZED, R.fail(ResultCode.UNAUTHORIZED));
			return;
		}
		try {
			StpUtil.checkPermission(MonitorConstants.PERM_MONITOR_LIST);
		} catch (NotPermissionException e) {
			write(response, HttpServletResponse.SC_FORBIDDEN, R.fail(ResultCode.FORBIDDEN));
			return;
		}
		chain.doFilter(request, response);
	}

	/**
	 * fail-closed：归一后落在 /actuator 段下默认需登录+监控权限码，仅 health/info 公开白名单（G1 探活契约）——
	 * 未来运维向 exposure.include 追加 env/heapdump 等敏感端点也不会裸奔。
	 */
	boolean isGuarded(HttpServletRequest request) {
		return isGuardedPath(lookupPath(request));
	}

	/** 应用内查找路径：与 DispatcherServlet/HandlerMapping 使用的 UrlPathHelper 默认策略对齐 */
	static String lookupPath(HttpServletRequest request) {
		return PATH_HELPER.getLookupPathForRequest(request);
	}

	/**
	 * 段感知匹配归一后的路径：/actuator 及其子路径需鉴权；health/info（及子路径）公开；
	 * 不误伤 /actuatorx 等非 actuator 前缀。
	 */
	static boolean isGuardedPath(String path) {
		if (path == null || !isUnder(path, ACTUATOR_ROOT)) {
			return false;
		}
		for (String pub : MonitorConstants.ACTUATOR_PUBLIC) {
			if (isUnder(path, pub)) {
				return false;
			}
		}
		return true;
	}

	/** exact 或 prefix + "/"，避免 startsWith("/actuator") 误伤 /actuatorx */
	static boolean isUnder(String path, String prefix) {
		return path.equals(prefix) || path.startsWith(prefix + "/");
	}

	private void write(HttpServletResponse response, int status, R<Void> body) throws IOException {
		response.setStatus(status);
		response.setContentType(MediaType.APPLICATION_JSON_VALUE);
		response.setCharacterEncoding(StandardCharsets.UTF_8.name());
		response.getWriter().write(objectMapper.writeValueAsString(body));
	}
}
