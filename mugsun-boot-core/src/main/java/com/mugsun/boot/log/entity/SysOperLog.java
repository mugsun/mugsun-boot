package com.mugsun.boot.log.entity;

import com.mugsun.core.mybatis.base.BaseEntity;
import com.mybatisflex.annotation.Table;

/**
 * 操作日志
 */
@Table("sys_oper_log")
public class SysOperLog extends BaseEntity {

	private String title;
	private String method;
	private String requestMethod;
	private String requestUri;
	private String operator;
	private String ip;
	private String params;
	private Long duration;
	private Integer status;
	private String errorMsg;

	/** 操作人显示名（展示用，非表列：operator 存的是用户 id，page 富化回填 昵称/用户名，用户已删则回退原 id） */
	@com.mybatisflex.annotation.Column(ignore = true)
	private String operatorName;
	/** 操作所属租户（@Async 落库经 TenantTaskDecorator 透传，异步不丢隔离） */
	private String tenantId;
	/** 防篡改：前一条哈希 / 本条 SM3 哈希 / 本条 SM2 签名 */
	private String prevHash;
	private String recordHash;
	private String sign;

	public String getPrevHash() {
		return prevHash;
	}

	public void setPrevHash(String prevHash) {
		this.prevHash = prevHash;
	}

	public String getRecordHash() {
		return recordHash;
	}

	public void setRecordHash(String recordHash) {
		this.recordHash = recordHash;
	}

	public String getSign() {
		return sign;
	}

	public void setSign(String sign) {
		this.sign = sign;
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

	public String getOperator() {
		return operator;
	}

	public void setOperator(String operator) {
		this.operator = operator;
	}

	public String getOperatorName() {
		return operatorName;
	}

	public void setOperatorName(String operatorName) {
		this.operatorName = operatorName;
	}

	public String getIp() {
		return ip;
	}

	public void setIp(String ip) {
		this.ip = ip;
	}

	public String getParams() {
		return params;
	}

	public void setParams(String params) {
		this.params = params;
	}

	public Long getDuration() {
		return duration;
	}

	public void setDuration(Long duration) {
		this.duration = duration;
	}

	public Integer getStatus() {
		return status;
	}

	public void setStatus(Integer status) {
		this.status = status;
	}

	public String getErrorMsg() {
		return errorMsg;
	}

	public void setErrorMsg(String errorMsg) {
		this.errorMsg = errorMsg;
	}

	public String getTenantId() {
		return tenantId;
	}

	public void setTenantId(String tenantId) {
		this.tenantId = tenantId;
	}
}
