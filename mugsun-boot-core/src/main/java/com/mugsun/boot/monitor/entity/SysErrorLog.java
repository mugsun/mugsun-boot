package com.mugsun.boot.monitor.entity;

import com.mugsun.core.mybatis.base.BaseEntity;
import com.mybatisflex.annotation.Table;

import java.time.LocalDateTime;

/**
 * 错误日志（全局未捕获异常落库）：栈顶四元组精确定位 + 处理闭环（未处理/已处理/已忽略认领）。
 */
@Table("sys_error_log")
public class SysErrorLog extends BaseEntity {

	/** 全站链路追踪号（与响应头 X-Trace-Id 一致，供排障对照） */
	private String traceId;
	private String requestUri;
	private String requestMethod;
	/** 操作人 loginId（未认证请求为空） */
	private String operator;
	/** 请求所属租户（Flex 租户插件落库时填充） */
	private String tenantId;
	/** 异常类全名 */
	private String exceptionClass;
	/** 异常消息（截断 500） */
	private String message;
	/** 栈顶定位四元组：首个 com.mugsun 栈帧（无则首帧）的类/文件/方法/行号 */
	private String locationClass;
	private String locationFile;
	private String locationMethod;
	private Integer locationLine;
	/** 完整堆栈（截断 8000） */
	private String stacktrace;
	/** 处理状态：0 未处理 / 1 已处理 / 2 已忽略 */
	private Integer status;
	private String handleUser;
	private String handleNote;
	private LocalDateTime handleTime;

	public String getTraceId() {
		return traceId;
	}

	public void setTraceId(String traceId) {
		this.traceId = traceId;
	}

	public String getRequestUri() {
		return requestUri;
	}

	public void setRequestUri(String requestUri) {
		this.requestUri = requestUri;
	}

	public String getRequestMethod() {
		return requestMethod;
	}

	public void setRequestMethod(String requestMethod) {
		this.requestMethod = requestMethod;
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

	public String getExceptionClass() {
		return exceptionClass;
	}

	public void setExceptionClass(String exceptionClass) {
		this.exceptionClass = exceptionClass;
	}

	public String getMessage() {
		return message;
	}

	public void setMessage(String message) {
		this.message = message;
	}

	public String getLocationClass() {
		return locationClass;
	}

	public void setLocationClass(String locationClass) {
		this.locationClass = locationClass;
	}

	public String getLocationFile() {
		return locationFile;
	}

	public void setLocationFile(String locationFile) {
		this.locationFile = locationFile;
	}

	public String getLocationMethod() {
		return locationMethod;
	}

	public void setLocationMethod(String locationMethod) {
		this.locationMethod = locationMethod;
	}

	public Integer getLocationLine() {
		return locationLine;
	}

	public void setLocationLine(Integer locationLine) {
		this.locationLine = locationLine;
	}

	public String getStacktrace() {
		return stacktrace;
	}

	public void setStacktrace(String stacktrace) {
		this.stacktrace = stacktrace;
	}

	public Integer getStatus() {
		return status;
	}

	public void setStatus(Integer status) {
		this.status = status;
	}

	public String getHandleUser() {
		return handleUser;
	}

	public void setHandleUser(String handleUser) {
		this.handleUser = handleUser;
	}

	public String getHandleNote() {
		return handleNote;
	}

	public void setHandleNote(String handleNote) {
		this.handleNote = handleNote;
	}

	public LocalDateTime getHandleTime() {
		return handleTime;
	}

	public void setHandleTime(LocalDateTime handleTime) {
		this.handleTime = handleTime;
	}
}
