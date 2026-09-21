package com.mugsun.boot.serial.entity;

import com.mugsun.core.mybatis.base.BaseEntity;
import com.mybatisflex.annotation.Column;
import com.mybatisflex.annotation.Table;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 单号生成记录：每业务每天一条，累计当日生成量与末值
 */
@Table("sys_serial_number_record")
public class SysSerialNumberRecord extends BaseEntity {

	/** 业务编码 */
	private String serialCode;
	/** 记录日期 */
	private LocalDate recordDate;
	/** 当日末值 */
	private Long lastNumber;
	/** 末次生成时间 */
	private LocalDateTime lastTime;
	/** 当日累计生成数（列名避开 PG 关键字 count） */
	@Column("gen_count")
	private Long count;

	public String getSerialCode() {
		return serialCode;
	}

	public void setSerialCode(String serialCode) {
		this.serialCode = serialCode;
	}

	public LocalDate getRecordDate() {
		return recordDate;
	}

	public void setRecordDate(LocalDate recordDate) {
		this.recordDate = recordDate;
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

	public Long getCount() {
		return count;
	}

	public void setCount(Long count) {
		this.count = count;
	}
}
