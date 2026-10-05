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
		if (e == null) {
			throw new ServiceException("请填写单号编码");
		}
		SerialRuleType type = SerialRuleType.parse(e.getRuleType());
		if (type == null) {
			throw new ServiceException("周期请选择不重置、按年、按月或按日");
		}
		String format = e.getFormat() == null ? "" : e.getFormat();
		if (format.isBlank()) {
			throw new ServiceException("请填写单号格式。数字位写成 [nnnnn]，日期可以写成 [yyyy][mm][dd]");
		}
		int start = format.indexOf("[n");
		int end = format.indexOf("n]");
		if (start < 0 || end < 0 || end <= start) {
			throw new ServiceException("格式里要有数字占位，例如 [nnnnn]。请补上后再保存");
		}
		Integer range = e.getStepRandomRange();
		if (range == null || range < 1 || range > 100) {
			throw new ServiceException("步长随机上限请填写 1 到 100 的整数。填 1 表示每次加 1");
		}
		// 周期占位必须出现在格式里，否则序号按周期重置、单号却看不出日期，跨周期会重号
		if (type == SerialRuleType.YEAR && !format.contains(SerialRuleType.YEAR.getValue())) {
			throw new ServiceException("按年重置时，格式里要包含 [yyyy]。请补上，否则跨年会重号");
		}
		if (type == SerialRuleType.MONTH && !format.contains(SerialRuleType.MONTH.getValue())) {
			throw new ServiceException("按月重置时，格式里要包含 [mm]。请补上，否则跨月会重号");
		}
		if (type == SerialRuleType.DAY && !format.contains(SerialRuleType.DAY.getValue())) {
			throw new ServiceException("按日重置时，格式里要包含 [dd]。请补上，否则跨日会重号");
		}
		return new SerialNumberInfo(e.getCode(), type,
			e.getInitNumber() == null ? 0L : e.getInitNumber(),
			format, range, end - start, "\\[" + format.substring(start + 1, end + 1) + "\\]",
			format.contains(SerialRuleType.YEAR.getValue()),
			format.contains(SerialRuleType.MONTH.getValue()),
			format.contains(SerialRuleType.DAY.getValue()));
	}
}
