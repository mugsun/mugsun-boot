package com.mugsun.boot.tenant;

/**
 * 租户隔离失败：对租户表执行操作却无任何租户上下文（既未解析到租户、也未显式忽略）。
 * fail-closed 抛出，杜绝静默返回全量数据的越权风险。
 */
public class TenantException extends RuntimeException {

	public TenantException(String message) {
		super(message);
	}
}
