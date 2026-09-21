package com.mugsun.boot.serial.entity;

import com.mugsun.core.mybatis.base.BaseEntity;
import com.mybatisflex.annotation.Table;

import java.time.LocalDateTime;

/**
 * 单号生成规则：按 code 取规则，format 含 [yyyy][mm][dd] 周期占位与 [n..n] 数字占位
 */
@Table("sys_serial_number")
public class SysSerialNumber extends BaseEntity {

	/** 业务编码（生成入参，唯一） */
	private String code;
	/** 业务名称 */
	private String businessName;
	/** 格式：如 DK[yyyy][mm][dd]NO[nnnnn] */
	private String format;
	/** 周期规则：none/year/month/day */
	private String ruleType;
	/** 初始值（每周期首号 = initNumber + 步长） */
	private Long initNumber;
	/** 步长随机上限（1 表示固定步长 1） */
	private Integer stepRandomRange;
	/** 上次产生的号 */
	private Long lastNumber;
	/** 上次产生时间 */
	private LocalDateTime lastTime;
	/** 备注 */
	private String remark;

	public String getCode() {
		return code;
	}

	public void setCode(String code) {
		this.code = code;
	}

	public String getBusinessName() {
		return businessName;
	}

	public void setBusinessName(String businessName) {
		this.businessName = businessName;
	}

	public String getFormat() {
		return format;
	}

	public void setFormat(String format) {
		this.format = format;
	}

	public String getRuleType() {
		return ruleType;
	}

	public void setRuleType(String ruleType) {
		this.ruleType = ruleType;
	}

	public Long getInitNumber() {
		return initNumber;
	}

	public void setInitNumber(Long initNumber) {
		this.initNumber = initNumber;
	}

	public Integer getStepRandomRange() {
		return stepRandomRange;
	}

	public void setStepRandomRange(Integer stepRandomRange) {
		this.stepRandomRange = stepRandomRange;
	}

	public Long getLastNumber() {
		return lastNumber;
	}

	public void setLastNumber(Long lastNumber) {
		this.lastNumber = lastNumber;
	}

	public LocalDateTime getLastTime() {
		return lastTime;
	}

	public void setLastTime(LocalDateTime lastTime) {
		this.lastTime = lastTime;
	}

	public String getRemark() {
		return remark;
	}

	public void setRemark(String remark) {
		this.remark = remark;
	}
}
