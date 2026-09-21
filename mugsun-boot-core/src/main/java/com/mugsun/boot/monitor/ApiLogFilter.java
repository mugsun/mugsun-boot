package com.mugsun.boot.monitor;

import cn.dev33.satoken.stp.StpUtil;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mugsun.boot.common.constant.MonitorConstants;
import com.mugsun.boot.log.OperationLog;
import com.mugsun.boot.monitor.entity.SysApiLog;
import com.mugsun.boot.security.XssRequestWrapper;
import com.mugsun.boot.system.service.ParamService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerExecutionChain;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 全局访问日志过滤器（@Order 紧随 XssFilter 的 -100 之后）：
 * 直接读 {@link XssRequestWrapper#cachedBody()} 的<strong>未净化原文</strong>抓 body（避 Xss 污染），
 * 全请求覆盖（含 GET）；标题按「@OperationLog value ＞ Swagger @Operation summary ＞ @Tag name ＞ uri」回退解析；
 * 参数经 {@link ApiParamMask} 结构化递归脱敏 + 截断后异步落库。
 * <p>采样仅对 GET 生效（写操作有 oper_log 留痕）；慢接口（超 monitor.access-log.slow-ms）必记且 slow=1，不受采样影响。
 */
@Component
@Order(-90)
public class ApiLogFilter extends OncePerRequestFilter {

	private final ApiLogService apiLogService;
	private final ParamService paramService;
	private final ObjectMapper objectMapper;
	private final RequestMappingHandlerMapping handlerMapping;

	public ApiLogFilter(ApiLogService apiLogService, ParamService paramService,
						ObjectMapper objectMapper,
						@Qualifier("requestMappingHandlerMapping") RequestMappingHandlerMapping handlerMapping) {
		this.apiLogService = apiLogService;
		this.paramService = paramService;
		this.objectMapper = objectMapper;
		this.handlerMapping = handlerMapping;
	}

	@Override
	protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
		throws ServletException, IOException {
		if (isExcluded(request.getRequestURI()) || isMultipart(request)) {
			chain.doFilter(request, response);
			return;
		}
		long start = System.currentTimeMillis();
		// 前置解析处理器方法（DispatcherServlet 之前路径匹配即已确定；链尾再解析可能受转发/异常影响失真）
		HandlerMethod handler = resolveHandler(request);
		int status = HttpServletResponse.SC_OK;
		try {
			chain.doFilter(request, response);
			status = response.getStatus();
		} catch (Exception e) {
			status = HttpServletResponse.SC_INTERNAL_SERVER_ERROR;
			throw e;
		} finally {
			try {
				record(request, handler, status, System.currentTimeMillis() - start);
			} catch (Exception ignore) {
				// 访问日志是尽力而为通道：任何异常绝不反噬请求链路
			}
		}
	}

	private void record(HttpServletRequest request, HandlerMethod handler, int status, long duration) {
		boolean slow = duration >= slowMs();
		// 采样仅对 GET 生效；慢接口必记
		if (!slow && "GET".equalsIgnoreCase(request.getMethod()) && !sampled()) {
			return;
		}
		SysApiLog record = new SysApiLog();
		record.setTraceId(MDC.get(MonitorConstants.TRACE_MDC_KEY));
		record.setRequestMethod(request.getMethod());
		record.setRequestUri(request.getRequestURI());
		record.setIp(request.getRemoteAddr());
		record.setUserAgent(truncate(request.getHeader("User-Agent"), MonitorConstants.UA_MAX_LEN));
		record.setStatus(status);
		record.setDuration(duration);
		record.setSlow(slow ? 1 : 0);
		record.setParams(extractParams(request));
		Object summary = request.getAttribute(MonitorConstants.ERROR_SUMMARY_ATTR);
		if (summary != null) {
			record.setErrorMsg(truncate(summary.toString(), MonitorConstants.ERROR_MSG_MAX_LEN));
		}
		fillOperator(record);
		fillHandler(record, request, handler);
		apiLogService.saveAsync(record);
	}

	/** 参数摘要：body 走未净化原文（XssRequestWrapper 缓存）递归脱敏；无 body（GET/form）走参数表脱敏 */
	private String extractParams(HttpServletRequest request) {
		if (request instanceof XssRequestWrapper wrapper) {
			byte[] body = wrapper.cachedBody();
			if (body != null && body.length > 0) {
				return ApiParamMask.maskJson(objectMapper, new String(body, StandardCharsets.UTF_8));
			}
		}
		return ApiParamMask.maskParams(objectMapper, request.getParameterMap());
	}

	/** 前置解析处理器方法：路径匹配失败（404 等）返回 null，标题回退 uri */
	private HandlerMethod resolveHandler(HttpServletRequest request) {
		try {
			HandlerExecutionChain chain = handlerMapping.getHandler(request);
			if (chain != null && chain.getHandler() instanceof HandlerMethod hm) {
				return hm;
			}
		} catch (Exception ignore) {
		}
		return null;
	}

	/** 标题/处理器方法：注解元数据回退链（全库零注解时容忍缺失退化为 uri） */
	private void fillHandler(SysApiLog record, HttpServletRequest request, HandlerMethod hm) {
		String title = null;
		if (hm != null) {
			record.setMethod(hm.getBeanType().getName() + "." + hm.getMethod().getName());
			OperationLog op = hm.getMethodAnnotation(OperationLog.class);
			if (op != null && !op.value().isBlank()) {
				title = op.value();
			} else {
				Operation operation = hm.getMethodAnnotation(Operation.class);
				if (operation != null && !operation.summary().isBlank()) {
					title = operation.summary();
				} else {
					Tag tag = hm.getBeanType().getAnnotation(Tag.class);
					if (tag != null && !tag.name().isBlank()) {
						title = tag.name();
					}
				}
			}
		}
		record.setTitle(title == null ? request.getRequestURI() : title);
	}

	private void fillOperator(SysApiLog record) {
		try {
			Object loginId = StpUtil.getLoginIdDefaultNull();
			if (loginId != null) {
				record.setOperator(loginId.toString());
			}
		} catch (Exception ignore) {
		}
	}

	/** GET 采样：sample-rate 百分比命中（sys_param 热更，JetCache 本地缓存，逐请求读开销可忽略） */
	private boolean sampled() {
		return ThreadLocalRandom.current().nextInt(100) < sampleRate();
	}

	private int sampleRate() {
		String value = paramService.getValue(MonitorConstants.PARAM_SAMPLE_RATE);
		try {
			int rate = value == null ? MonitorConstants.DEFAULT_SAMPLE_RATE : Integer.parseInt(value.trim());
			// 钳制 [0,100]：负值会静默停掉全部 GET 访问日志
			return rate < 0 || rate > 100 ? MonitorConstants.DEFAULT_SAMPLE_RATE : rate;
		} catch (NumberFormatException e) {
			return MonitorConstants.DEFAULT_SAMPLE_RATE;
		}
	}

	private long slowMs() {
		String value = paramService.getValue(MonitorConstants.PARAM_SLOW_MS);
		try {
			long ms = value == null ? MonitorConstants.DEFAULT_SLOW_MS : Long.parseLong(value.trim());
			return ms < 1 ? MonitorConstants.DEFAULT_SLOW_MS : ms;
		} catch (NumberFormatException e) {
			return MonitorConstants.DEFAULT_SLOW_MS;
		}
	}

	private boolean isMultipart(HttpServletRequest request) {
		String contentType = request.getContentType();
		return contentType != null && contentType.toLowerCase().startsWith("multipart/");
	}

	private boolean isExcluded(String uri) {
		if (uri == null) {
			return true;
		}
		for (String prefix : MonitorConstants.API_LOG_EXCLUDES) {
			if (uri.startsWith(prefix)) {
				return true;
			}
		}
		return false;
	}

	private String truncate(String s, int max) {
		if (s == null) {
			return null;
		}
		return s.length() > max ? s.substring(0, max) : s;
	}
}
