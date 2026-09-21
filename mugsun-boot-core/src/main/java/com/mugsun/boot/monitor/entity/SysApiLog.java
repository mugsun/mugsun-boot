package com.mugsun.boot.monitor.entity;

import com.mugsun.core.mybatis.base.BaseEntity;
import com.mybatisflex.annotation.Table;

/**
 * 访问日志（轻表无哈希链，全量请求流量；写操作留痕走 sys_oper_log 防篡改链，互不复用）。
 */
@Table("sys_api_log")
public class SysApiLog extends BaseEntity {

	/** 全站链路追踪号（TraceIdFilter 生成/沿用网关下发） */
	private String traceId;
	/** 接口标题：@OperationLog value ＞ Swagger @Operation summary ＞ @Tag name ＞ uri 回退链 */
	private String title;
	/** 处理器方法（类全名.方法名） */
	private String method;
	private String requestMethod;
	private String requestUri;
	private String ip;
	private String userAgent;
	/** 操作人 loginId（未认证请求为空） */
	private String operator;
	/** 请求所属租户（Flex 租户插件落库时填充，@Async 经 TenantTaskDecorator 透传） */
	private String tenantId;
	/** HTTP 响应状态码 */
	private Integer status;
	private Long duration;
	/** 慢接口标记：1 超过 monitor.access-log.slow-ms（慢接口必记不受采样影响） */
	private Integer slow;
	/** 参数摘要（结构化递归脱敏 + 截断 2000） */
	private String params;
	/** 异常摘要（500 时由 ErrorLogRecorder 经请求属性回填） */
	private String errorMsg;

	public String getTraceId() {
		return traceId;
	}

	public void setTraceId(String traceId) {
		this.traceId = traceId;
	}

	public String getTitle() {
		return title;
	}

	public void setTitle(String title) {
		this.title = title;
	}

	public String getMethod() {
		return method;
	}

	public void setMethod(String method) {
		this.method = method;
	}

	public String getRequestMethod() {
		return requestMethod;
	}

	public void setRequestMethod(String requestMethod) {
		this.requestMethod = requestMethod;
	}

	public String getRequestUri() {
		return requestUri;
	}

	public void setRequestUri(String requestUri) {
		this.requestUri = requestUri;
	}

	public String getIp() {
		return ip;
	}

	public void setIp(String ip) {
		this.ip = ip;
	}

	public String getUserAgent() {
		return userAgent;
	}

	public void setUserAgent(String userAgent) {
		this.userAgent = userAgent;
	}

	public String getOperator() {
		return operator;
	}

	public void setOperator(String operator) {
		this.operator = operator;
	}

	public String getTenantId() {
		return tenantId;
	}

	public void setTenantId(String tenantId) {
		this.tenantId = tenantId;
	}

	public Integer getStatus() {
		return status;
	}

	public void setStatus(Integer status) {
		this.status = status;
	}

	public Long getDuration() {
		return duration;
	}

	public void setDuration(Long duration) {
		this.duration = duration;
	}

	public Integer getSlow() {
		return slow;
	}

	public void setSlow(Integer slow) {
		this.slow = slow;
	}

	public String getParams() {
		return params;
	}

	public void setParams(String params) {
		this.params = params;
	}

	public String getErrorMsg() {
		return errorMsg;
	}

	public void setErrorMsg(String errorMsg) {
		this.errorMsg = errorMsg;
	}
}
