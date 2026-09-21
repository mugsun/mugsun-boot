package com.mugsun.boot.monitor;

import cn.dev33.satoken.stp.StpUtil;
import com.mugsun.boot.common.constant.MonitorConstants;
import com.mugsun.boot.monitor.entity.SysErrorLog;
import com.mugsun.core.web.handler.ErrorLogListener;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.MDC;
import org.springframework.stereotype.Component;

import java.io.PrintWriter;
import java.io.StringWriter;

/**
 * 错误日志记录器：core 全局异常兜底发布的监听实现（boot 侧落库闭环）。
 * <p>在请求线程内同步捕获上下文（traceId/操作人/请求信息，异步线程无请求上下文不可取），
 * 实体填齐后交 {@link ErrorLogService} 异步落库；并向请求属性回填异常摘要供访问日志记录。
 */
@Component
public class ErrorLogRecorder implements ErrorLogListener {

	/** 栈顶定位包前缀：首个业务栈帧才是故障点（跳过 JDK/反射/代理帧） */
	private static final String APP_PACKAGE = "com.mugsun";

	private final ErrorLogService errorLogService;

	public ErrorLogRecorder(ErrorLogService errorLogService) {
		this.errorLogService = errorLogService;
	}

	@Override
	public void onError(HttpServletRequest request, Throwable error) {
		SysErrorLog record = new SysErrorLog();
		record.setTraceId(MDC.get(MonitorConstants.TRACE_MDC_KEY));
		record.setRequestUri(request.getRequestURI());
		record.setRequestMethod(request.getMethod());
		record.setExceptionClass(error.getClass().getName());
		record.setMessage(truncate(error.getMessage(), MonitorConstants.ERROR_MSG_MAX_LEN));
		fillLocation(record, error);
		record.setStacktrace(truncate(stacktrace(error), MonitorConstants.STACK_MAX_LEN));
		record.setStatus(MonitorConstants.ERROR_STATUS_TODO);
		fillOperator(record);
		// 回填异常摘要：访问日志 error_msg 取此（同一线程请求属性，Filter 在链尾读取）
		request.setAttribute(MonitorConstants.ERROR_SUMMARY_ATTR,
			error.getClass().getSimpleName() + ": " + record.getMessage());
		errorLogService.saveAsync(record);
	}

	/** 栈顶四元组：首个 com.mugsun 业务栈帧，无则回退首帧，再退化为异常抛出点未知 */
	private void fillLocation(SysErrorLog record, Throwable error) {
		StackTraceElement[] stack = error.getStackTrace();
		if (stack == null || stack.length == 0) {
			return;
		}
		StackTraceElement top = stack[0];
		for (StackTraceElement e : stack) {
			if (e.getClassName().startsWith(APP_PACKAGE)) {
				top = e;
				break;
			}
		}
		record.setLocationClass(top.getClassName());
		record.setLocationFile(top.getFileName());
		record.setLocationMethod(top.getMethodName());
		record.setLocationLine(top.getLineNumber());
	}

	private void fillOperator(SysErrorLog record) {
		try {
			Object loginId = StpUtil.getLoginIdDefaultNull();
			if (loginId != null) {
				record.setOperator(loginId.toString());
			}
		} catch (Exception ignore) {
		}
	}

	private String stacktrace(Throwable error) {
		StringWriter sw = new StringWriter();
		error.printStackTrace(new PrintWriter(sw));
		return sw.toString();
	}

	private String truncate(String s, int max) {
		if (s == null) {
			return null;
		}
		return s.length() > max ? s.substring(0, max) + "...(truncated)" : s;
	}
}
