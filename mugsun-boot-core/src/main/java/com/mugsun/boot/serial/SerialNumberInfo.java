package com.mugsun.boot.serial;

import com.mugsun.boot.serial.entity.SysSerialNumber;
import com.mugsun.core.tool.exception.ServiceException;

/**
 * 规则解析结果：由 format 一次解析出周期标志与数字占位，供生成时复用
 */
public record SerialNumberInfo(String code, SerialRuleType ruleType, long initNumber, String format,
							   int stepRandomRange, int numberCount, String numberFormat,
							   boolean haveYear, boolean haveMonth, boolean haveDay) {

	public static SerialNumberInfo parse(SysSerialNumber e) {
		SerialRuleType type = SerialRuleType.parse(e.getRuleType());
		if (type == null) {
			throw new ServiceException("单号规则周期非法: " + e.getRuleType());
		}
		String format = e.getFormat();
		int start = format == null ? -1 : format.indexOf("[n");
		int end = format == null ? -1 : format.indexOf("n]");
		if (start < 0 || end < 0 || end <= start) {
			throw new ServiceException("单号格式缺少 [n..n] 数字占位: " + e.getCode());
		}
		Integer range = e.getStepRandomRange();
		if (range == null || range < 1) {
			throw new ServiceException("步长随机上限须 ≥ 1: " + e.getCode());
		}
		// 周期占位一致性：DAY/MONTH/YEAR 规则的 format 必须含对应周期段，
		// 否则序号按周期重置而格式串无周期值 → 跨周期必重号
		if (type != SerialRuleType.NONE) {
			String need = type.getValue();
			if (!format.contains(need)) {
				throw new ServiceException("周期规则的单号格式须包含周期占位 " + need + "，否则跨周期重号: " + e.getCode());
			}
		}
		return new SerialNumberInfo(e.getCode(), type,
			e.getInitNumber() == null ? 0L : e.getInitNumber(),
			format, range, end - start, "\\[" + format.substring(start + 1, end + 1) + "\\]",
			format.contains(SerialRuleType.YEAR.getValue()),
			format.contains(SerialRuleType.MONTH.getValue()),
			format.contains(SerialRuleType.DAY.getValue()));
	}
}
