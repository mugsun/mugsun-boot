package com.mugsun.boot.serial;

/**
 * 单号周期规则：占位符与跨周期重置依据
 */
public enum SerialRuleType {

	NONE("", "", "无周期"),
	YEAR("[yyyy]", "\\[yyyy\\]", "年"),
	MONTH("[mm]", "\\[mm\\]", "年月"),
	DAY("[dd]", "\\[dd\\]", "年月日");

	/** 占位符 */
	private final String value;
	/** 替换用正则 */
	private final String regex;
	/** 描述 */
	private final String desc;

	SerialRuleType(String value, String regex, String desc) {
		this.value = value;
		this.regex = regex;
		this.desc = desc;
	}

	public String getValue() {
		return value;
	}

	public String getRegex() {
		return regex;
	}

	public String getDesc() {
		return desc;
	}

	/** 按名称解析（忽略大小写），非法返回 null */
	public static SerialRuleType parse(String name) {
		if (name == null) {
			return null;
		}
		for (SerialRuleType t : values()) {
			if (t.name().equalsIgnoreCase(name)) {
				return t;
			}
		}
		return null;
	}
}
