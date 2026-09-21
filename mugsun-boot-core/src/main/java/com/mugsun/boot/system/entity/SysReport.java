package com.mugsun.boot.system.entity;

import com.mugsun.core.mybatis.base.BaseEntity;
import com.mybatisflex.annotation.Table;

/**
 * 报表定义
 */
@Table("sys_report")
public class SysReport extends BaseEntity {

	private String reportName;
	private String reportKey;
	private String chartType;
	/** 多图表仪表盘配置 JSON：[{dataset,chartType,title}] */
	private String charts;
	private String remark;

	public String getReportName() {
		return reportName;
	}

	public void setReportName(String reportName) {
		this.reportName = reportName;
	}

	public String getReportKey() {
		return reportKey;
	}

	public void setReportKey(String reportKey) {
		this.reportKey = reportKey;
	}

	public String getChartType() {
		return chartType;
	}

	public void setChartType(String chartType) {
		this.chartType = chartType;
	}

	public String getCharts() {
		return charts;
	}

	public void setCharts(String charts) {
		this.charts = charts;
	}

	public String getRemark() {
		return remark;
	}

	public void setRemark(String remark) {
		this.remark = remark;
	}
}
