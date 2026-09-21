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

import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * actuator 端点鉴权：prometheus/metrics 需登录 + {@code sys:monitor:list} 权限码；
 * health/info 保持公开（G1 契约：部署探活不鉴权）。段感知前缀匹配，不误伤 /actuator 根与公开端点。
 */
@Component
@Order(-80)
public class ActuatorAuthFilter extends OncePerRequestFilter {

	private final ObjectMapper objectMapper;

	public ActuatorAuthFilter(ObjectMapper objectMapper) {
		this.objectMapper = objectMapper;
	}

	@Override
	protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
		throws ServletException, IOException {
		if (!isGuarded(request.getRequestURI())) {
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

	/** fail-closed：/actuator/** 默认全部需登录+监控权限码，仅 health/info 公开白名单（G1 探活契约）——
	 *  未来运维向 exposure.include 追加 env/heapdump 等敏感端点也不会裸奔 */
	private boolean isGuarded(String uri) {
		if (uri == null || !uri.startsWith("/actuator")) {
			return false;
		}
		for (String pub : MonitorConstants.ACTUATOR_PUBLIC) {
			if (uri.equals(pub) || uri.startsWith(pub + "/")) {
				return false;
			}
		}
		return true;
	}

	private void write(HttpServletResponse response, int status, R<Void> body) throws IOException {
		response.setStatus(status);
		response.setContentType(MediaType.APPLICATION_JSON_VALUE);
		response.setCharacterEncoding(StandardCharsets.UTF_8.name());
		response.getWriter().write(objectMapper.writeValueAsString(body));
	}
}
