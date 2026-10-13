package com.mugsun.boot.captcha.store;

/**
 * 验证码键值存储。所有「读取即作废」都必须走 {@link #take}（原子删除后返回原值），保证一次性。
 */
public interface CaptchaStore {

	void put(String key, String value, long ttlSeconds);

	/** 原子取出并删除；不存在或已过期返回 null */
	String take(String key);

	/** 只读（仅限流计数/测试使用，禁止用于核销） */
	String peek(String key);

	/** 自增计数；首次创建时设置过期 */
	long increment(String key, long ttlSeconds);

	/** 只读取计数（不存在/过期为 0），不改变计数 */
	long count(String key);

	/**
	 * 原子「未达上限才自增」：当前值 &lt; max 时自增并（首次/缺失过期时）设过期，返回自增后的值；
	 * 已达上限返回 -1 且不改变计数。
	 */
	long incrementIfBelow(String key, long max, long ttlSeconds);

	/** 剩余秒数；不存在返回 0 */
	long ttlSeconds(String key);

	void delete(String key);
}
