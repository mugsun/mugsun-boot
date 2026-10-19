package com.mugsun.boot.demo;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.Ordered;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerExceptionResolver;
import org.springframework.web.servlet.ModelAndView;

import java.sql.SQLException;

/**
 * 只读演示模式兜底：漏网的写库（只读账号 → PG insufficient_privilege 42501）统一返回 403「演示环境只读」，不出 500。
 * <p>用最高优先级的 {@link HandlerExceptionResolver} 而不是 @ControllerAdvice：不是权限错误时返回 null，
 * 交回原有全局异常处理，其他数据库异常的响应格式不变。
 */
@Component
@ConditionalOnProperty(name = DemoConstants.ENABLED_PROPERTY, havingValue = "true")
public class DemoSqlPermissionResolver implements HandlerExceptionResolver, Ordered {

	/** PostgreSQL insufficient_privilege */
	public static final String PG_INSUFFICIENT_PRIVILEGE = "42501";

	private static final Logger log = LoggerFactory.getLogger(DemoSqlPermissionResolver.class);

	private final ObjectMapper objectMapper;

	public DemoSqlPermissionResolver(ObjectMapper objectMapper) {
		this.objectMapper = objectMapper;
	}

	@Override
	public int getOrder() {
		return Ordered.HIGHEST_PRECEDENCE;
	}

	@Override
	public ModelAndView resolveException(HttpServletRequest request, HttpServletResponse response, Object handler, Exception ex) {
		if (!isPermissionDenied(ex)) {
			return null;
		}
		log.warn("[demo] 拦下一次漏网的写库 {} {}：{}", request.getMethod(), request.getRequestURI(), rootMessage(ex));
		try {
			if (!response.isCommitted()) {
				DemoReadOnlyFilter.reject(response, objectMapper);
			}
		} catch (Exception writeFail) {
			log.debug("[demo] 写 403 响应失败：{}", writeFail.getMessage());
		}
		return new ModelAndView();
	}

	public static boolean isPermissionDenied(Throwable e) {
		for (Throwable t = e; t != null; t = t.getCause() == t ? null : t.getCause()) {
			if (t instanceof SQLException sql && PG_INSUFFICIENT_PRIVILEGE.equals(sql.getSQLState())) {
				return true;
			}
		}
		return false;
	}

	private static String rootMessage(Throwable e) {
		Throwable t = e;
		while (t.getCause() != null && t.getCause() != t) {
			t = t.getCause();
		}
		return t.getMessage();
	}
}
