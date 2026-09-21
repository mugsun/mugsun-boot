package com.mugsun.boot.system.excel;

import cn.idev.excel.annotation.ExcelProperty;

/**
 * 行政区划导入导出模型
 */
public class RegionExcel {

	@ExcelProperty("区划编码")
	private String code;

	@ExcelProperty("父级编码")
	private String parentCode;

	@ExcelProperty("名称")
	private String name;

	@ExcelProperty("层级")
	private Integer level;

	public String getCode() {
		return code;
	}

	public void setCode(String code) {
		this.code = code;
	}

	public String getParentCode() {
		return parentCode;
	}

	public void setParentCode(String parentCode) {
		this.parentCode = parentCode;
	}

	public String getName() {
		return name;
	}

	public void setName(String name) {
		this.name = name;
	}

	public Integer getLevel() {
		return level;
	}

	public void setLevel(Integer level) {
		this.level = level;
	}
}
