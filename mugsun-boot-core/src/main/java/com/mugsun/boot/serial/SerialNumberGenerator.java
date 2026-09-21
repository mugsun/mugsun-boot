package com.mugsun.boot.serial;

import java.util.List;

/**
 * 单号生成策略：DB 行锁 与 Redis 自增两种实现，门面按配置选主。
 */
public interface SerialNumberGenerator {

	/** 依据解析后的规则生成 count 个格式化单号 */
	List<String> generate(SerialNumberInfo info, int count);

	/** 策略标识：db / redis */
	String type();
}
