package com.mugsun.boot.ai.springai;

import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.ai.tool.metadata.ToolMetadata;

import java.util.Objects;

/**
 * 给工具带上发起请求时的租户：工具在没有租户的线程上执行时（流式对话），按这个租户执行，见 {@link TenantScope}。
 * 工具的名称、说明、参数都照原样。
 */
final class TenantBoundToolCallback implements ToolCallback {

	private final ToolCallback delegate;
	private final String tenantId;

	private TenantBoundToolCallback(ToolCallback delegate, String tenantId) {
		this.delegate = delegate;
		this.tenantId = tenantId;
	}

	/**
	 * 已经包过的不再套一层，只换成这次的租户；{@code tenantId} 为 {@code null} 时返回原来的工具，
	 * 不沿用以前包进去的租户。
	 */
	static ToolCallback of(ToolCallback callback, String tenantId) {
		ToolCallback raw = callback instanceof TenantBoundToolCallback bound ? bound.delegate : callback;
		return tenantId == null ? raw : new TenantBoundToolCallback(raw, tenantId);
	}

	ToolCallback delegate() {
		return delegate;
	}

	@Override
	public ToolDefinition getToolDefinition() {
		return delegate.getToolDefinition();
	}

	@Override
	public ToolMetadata getToolMetadata() {
		return delegate.getToolMetadata();
	}

	@Override
	public String call(String toolInput) {
		return TenantScope.run(tenantId, () -> delegate.call(toolInput));
	}

	@Override
	public String call(String toolInput, ToolContext toolContext) {
		return TenantScope.run(tenantId, () -> delegate.call(toolInput, toolContext));
	}

	@Override
	public boolean equals(Object o) {
		return o instanceof TenantBoundToolCallback that
			&& delegate.equals(that.delegate) && Objects.equals(tenantId, that.tenantId);
	}

	@Override
	public int hashCode() {
		return Objects.hash(delegate, tenantId);
	}

	@Override
	public String toString() {
		return delegate.toString();
	}
}
