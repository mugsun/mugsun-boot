package com.mugsun.boot.serial;

import cn.hutool.core.util.IdUtil;
import com.mugsun.boot.gen.DbDialects;
import com.mugsun.boot.gen.RuntimeSql;
import com.mugsun.boot.gen.SqlDialect;
import com.mugsun.boot.serial.entity.SysSerialNumber;
import com.mugsun.boot.serial.mapper.SysSerialNumberMapper;
import com.mybatisflex.core.query.QueryWrapper;
import com.mybatisflex.core.row.Db;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 单号生成器基类：跨周期重置、随机步长循环、占位格式化、末值与记录落库共用逻辑。
 */
public abstract class AbstractSerialNumberGenerator implements SerialNumberGenerator {

	protected final SysSerialNumberMapper serialMapper;

	protected AbstractSerialNumberGenerator(SysSerialNumberMapper serialMapper) {
		this.serialMapper = serialMapper;
	}

	/** 从末值循环生成 count 个纯数字（跨周期回到 initNumber，随机步长累加） */
	protected GenerateResult loop(Long lastNumber, LocalDateTime lastTime, SerialNumberInfo info, int count) {
		long number = needReset(lastTime, info.ruleType()) ? info.initNumber() : lastNumber;
		List<Long> numberList = new ArrayList<>(count);
		for (int i = 0; i < count; i++) {
			number += info.stepRandomRange() > 1 ? ThreadLocalRandom.current().nextInt(1, info.stepRandomRange() + 1) : 1;
			numberList.add(number);
		}
		return new GenerateResult(info.code(), number, LocalDateTime.now(), numberList);
	}

	/** 末次时间不在当前周期内则需重置（Redis 版预热判断当前周期亦复用） */
	protected boolean needReset(LocalDateTime lastTime, SerialRuleType ruleType) {
		if (lastTime == null) {
			return true;
		}
		LocalDateTime now = LocalDateTime.now();
		return switch (ruleType) {
			case YEAR -> lastTime.getYear() != now.getYear();
			case MONTH -> lastTime.getYear() != now.getYear() || lastTime.getMonthValue() != now.getMonthValue();
			case DAY -> lastTime.getYear() != now.getYear() || lastTime.getDayOfYear() != now.getDayOfYear();
			case NONE -> false;
		};
	}

	/** 替换 [yyyy][mm][dd] 周期占位与前补零的 [n..n] 数字占位 */
	protected List<String> format(GenerateResult result, SerialNumberInfo info) {
		LocalDate date = result.lastTime().toLocalDate();
		String base = info.format();
		if (info.haveYear()) {
			base = base.replaceAll(SerialRuleType.YEAR.getRegex(), String.valueOf(date.getYear()));
		}
		if (info.haveMonth()) {
			base = base.replaceAll(SerialRuleType.MONTH.getRegex(), pad(date.getMonthValue(), 2));
		}
		if (info.haveDay()) {
			base = base.replaceAll(SerialRuleType.DAY.getRegex(), pad(date.getDayOfMonth(), 2));
		}
		List<String> list = new ArrayList<>(result.numberList().size());
		for (Long number : result.numberList()) {
			list.add(base.replaceAll(info.numberFormat(), padNumber(number, info.numberCount())));
		}
		return list;
	}

	private String pad(int value, int width) {
		return String.format("%0" + width + "d", value);
	}

	private String padNumber(long number, int width) {
		String s = String.valueOf(number);
		return s.length() >= width ? s : "0".repeat(width - s.length()) + s;
	}

	/**
	 * 回写规则末值 + 累加当日生成记录（无当日记录则新建）。
	 *
	 * @param monotonic true 时末值单调不减（GREATEST，Redis 版并发回写防倒退）；
	 *                  false 时精确覆盖（DB 版行锁精确，且跨周期重置需允许变小）
	 */
	protected void persist(GenerateResult result, int count, boolean monotonic) {
		SqlDialect d = DbDialects.current();
		if (monotonic) {
			Db.updateBySql(RuntimeSql.updateSerialLastMonotonic(d),
				result.lastNumber(), result.lastTime(), result.lastTime(), result.code());
		} else {
			SysSerialNumber patch = new SysSerialNumber();
			patch.setLastNumber(result.lastNumber());
			patch.setLastTime(result.lastTime());
			serialMapper.updateByQuery(patch, QueryWrapper.create().eq("code", result.code()));
		}

		LocalDate today = result.lastTime().toLocalDate();
		// 单条 upsert：PG ON CONFLICT / Oracle·达梦 MERGE，靠 (serial_code, record_date) 归并
		Db.updateBySql(RuntimeSql.upsertSerialRecord(d),
			IdUtil.getSnowflakeNextId(), result.code(), today, result.lastNumber(), result.lastTime(), (long) count);
	}
}
