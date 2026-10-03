package com.mugsun.boot.ai.entity;

import com.mugsun.core.mybatis.base.BaseEntity;
import com.mybatisflex.annotation.Table;
import java.math.BigDecimal;
import java.time.LocalDateTime;

@Table("ai_quota")
public class AiQuota extends BaseEntity {

	private String tenantId;
	private String period;
	private Long tokenLimit;
	private BigDecimal amountLimit;
	@com.fasterxml.jackson.annotation.JsonAlias("warnPercent")
	private Integer warnRatio;
	private String overAction;
	private Long usedTokens;
	private BigDecimal usedAmount;
	private LocalDateTime resetTime;
	private Integer status;

	public String getTenantId() {
		return tenantId;
	}

	public void setTenantId(String tenantId) {
		this.tenantId = tenantId;
	}

	public String getPeriod() {
		return period;
	}

	public void setPeriod(String period) {
		this.period = period;
	}

	public Long getTokenLimit() {
		return tokenLimit;
	}

	public void setTokenLimit(Long tokenLimit) {
		this.tokenLimit = tokenLimit;
	}

	public BigDecimal getAmountLimit() {
		return amountLimit;
	}

	public void setAmountLimit(BigDecimal amountLimit) {
		this.amountLimit = amountLimit;
	}

	public Integer getWarnRatio() {
		return warnRatio;
	}

	public void setWarnRatio(Integer warnRatio) {
		this.warnRatio = warnRatio;
	}

	public Integer getWarnPercent() {
		return warnRatio;
	}

	public void setWarnPercent(Integer warnPercent) {
		this.warnRatio = warnPercent;
	}

	public String getScopeType() {
		return "tenant";
	}

	public String getOverAction() {
		return overAction;
	}

	public void setOverAction(String overAction) {
		this.overAction = overAction;
	}

	public Long getUsedTokens() {
		return usedTokens;
	}

	public void setUsedTokens(Long usedTokens) {
		this.usedTokens = usedTokens;
	}

	public BigDecimal getUsedAmount() {
		return usedAmount;
	}

	public void setUsedAmount(BigDecimal usedAmount) {
		this.usedAmount = usedAmount;
	}

	public LocalDateTime getResetTime() {
		return resetTime;
	}

	public void setResetTime(LocalDateTime resetTime) {
		this.resetTime = resetTime;
	}

	public Integer getStatus() {
		return status;
	}

	public void setStatus(Integer status) {
		this.status = status;
	}
}
