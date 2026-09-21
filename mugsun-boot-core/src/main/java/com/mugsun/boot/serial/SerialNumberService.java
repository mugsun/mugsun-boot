package com.mugsun.boot.serial;

import com.mugsun.boot.serial.entity.SysSerialNumber;
import com.mugsun.boot.serial.mapper.SysSerialNumberMapper;
import com.mugsun.core.tool.exception.ServiceException;
import com.mybatisflex.core.query.QueryWrapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 单号生成门面：按 code 取规则解析，委派配置选定的策略生成。
 */
@Service
public class SerialNumberService {

	/** 单次生成上限，防超大批量撑爆内存与自增 */
	private static final int MAX_COUNT = 1000;

	private final SysSerialNumberMapper serialMapper;
	private final Map<String, SerialNumberGenerator> generators;
	/** 生成策略：redis（默认）/ db */
	private final String provider;

	public SerialNumberService(SysSerialNumberMapper serialMapper, List<SerialNumberGenerator> generatorList,
							   @Value("${mugsun.serial-number.provider:redis}") String provider) {
		this.serialMapper = serialMapper;
		this.generators = generatorList.stream().collect(Collectors.toMap(SerialNumberGenerator::type, Function.identity()));
		this.provider = provider;
	}

	/** 生成单个单号 */
	public String generate(String code) {
		return generate(code, 1).get(0);
	}

	/** 生成 count 个单号 */
	public List<String> generate(String code, int count) {
		if (count < 1 || count > MAX_COUNT) {
			throw new ServiceException("生成数量须在 1~" + MAX_COUNT + " 之间");
		}
		SerialNumberGenerator generator = generators.get(provider);
		if (generator == null) {
			throw new ServiceException("未知单号策略: " + provider);
		}
		return generator.generate(loadInfo(code), count);
	}

	/** 按 code 取规则并解析（末值运行时由各策略另查，此处仅解析格式配置） */
	private SerialNumberInfo loadInfo(String code) {
		SysSerialNumber rule = serialMapper.selectOneByQuery(QueryWrapper.create().eq("code", code));
		if (rule == null) {
			throw new ServiceException("单号规则不存在: " + code);
		}
		return SerialNumberInfo.parse(rule);
	}
}
