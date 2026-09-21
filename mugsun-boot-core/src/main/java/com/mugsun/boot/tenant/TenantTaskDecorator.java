package com.mugsun.boot.tenant;

import org.slf4j.MDC;
import org.springframework.core.task.TaskDecorator;

import java.util.Map;

/**
 * 异步任务上下文透传：在提交线程（请求线程）捕获当前有效租户与 MDC（traceId），
 * 于工作线程（@Async/虚拟线程）设入 {@link TenantContext} / MDC，执行后严格清除——
 * 使异步/定时写入落到正确租户且日志携带原请求 traceId，杜绝虚拟线程复用导致的租户串号与链路断点。
 */
public class TenantTaskDecorator implements TaskDecorator {

	@Override
	public Runnable decorate(Runnable runnable) {
		String tenantId = TenantContext.current();
		Map<String, String> mdc = MDC.getCopyOfContextMap();
		return () -> {
			if (tenantId != null) {
				TenantContext.set(tenantId);
			}
			if (mdc != null) {
				MDC.setContextMap(mdc);
			}
			try {
				runnable.run();
			} finally {
				TenantContext.clear();
				MDC.clear();
			}
		};
	}
}
