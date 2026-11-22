package com.mugsun.boot.ai.springai;

import com.mugsun.boot.tenant.TenantContext;
import com.mugsun.boot.tenant.TenantException;

import java.util.function.Supplier;

/**
 * 换了线程也按发起请求时的租户执行。
 * <p>流式对话里，Spring AI 把工具放到别的线程执行，工具执行完再问模型的那几轮也在那个线程上；
 * 那里没有登录会话，查租户表会被拒绝。做法和 {@code TenantTaskDecorator} 对 @Async 任务一样：
 * 发起请求时记下租户，到了没有租户的线程再设上，用完恢复。
 */
final class TenantScope {

	private TenantScope() {
	}

	/** 当前线程有没有租户（登录会话里的，或者代码里显式指定的） */
	static boolean present() {
		try {
			TenantContext.resolveTenantIds();
			return true;
		} catch (TenantException e) {
			return false;
		}
	}

	/**
	 * 当前线程有租户就直接执行，以线程上的为准；没有就按 {@code tenantId} 执行。
	 * {@code tenantId} 也没有时照常执行，查租户表会照旧被拒绝。
	 */
	static <T> T run(String tenantId, Supplier<T> action) {
		if (tenantId == null || present()) {
			return action.get();
		}
		return TenantContext.execute(tenantId, action);
	}
}
