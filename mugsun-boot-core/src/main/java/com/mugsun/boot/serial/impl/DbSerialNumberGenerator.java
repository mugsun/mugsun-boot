package com.mugsun.boot.serial.impl;

import com.mugsun.boot.serial.AbstractSerialNumberGenerator;
import com.mugsun.boot.serial.GenerateResult;
import com.mugsun.boot.serial.SerialNumberInfo;
import com.mugsun.boot.serial.entity.SysSerialNumber;
import com.mugsun.boot.serial.mapper.SysSerialNumberMapper;
import com.mugsun.core.tool.exception.ServiceException;
import com.mybatisflex.core.query.QueryWrapper;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * DB 行锁实现：事务内 SELECT ... FOR UPDATE 串行化取末值，适合无 Redis 或强一致场景。
 */
@Component
public class DbSerialNumberGenerator extends AbstractSerialNumberGenerator {

	public DbSerialNumberGenerator(SysSerialNumberMapper serialMapper) {
		super(serialMapper);
	}

	@Override
	@Transactional(rollbackFor = Exception.class)
	public List<String> generate(SerialNumberInfo info, int count) {
		SysSerialNumber locked = serialMapper.selectOneByQuery(
			QueryWrapper.create().eq("code", info.code()).forUpdate());
		if (locked == null) {
			throw new ServiceException("单号规则不存在: " + info.code());
		}
		GenerateResult result = loop(locked.getLastNumber(), locked.getLastTime(), info, count);
		persist(result, count, false);
		return format(result, info);
	}

	@Override
	public String type() {
		return "db";
	}
}
