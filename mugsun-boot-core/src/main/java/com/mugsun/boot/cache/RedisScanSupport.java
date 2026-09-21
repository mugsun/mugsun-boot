package com.mugsun.boot.cache;

import org.springframework.data.redis.core.Cursor;
import org.springframework.data.redis.core.ScanOptions;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.util.ArrayList;
import java.util.List;

/**
 * Redis SCAN 遍历：避免 KEYS 在生产 keyspace 上阻塞。
 */
public final class RedisScanSupport {

	private RedisScanSupport() {
	}

	public static List<String> scan(StringRedisTemplate redis, String pattern) {
		List<String> keys = new ArrayList<>();
		try (Cursor<String> cursor = redis.scan(
			ScanOptions.scanOptions().match(pattern).count(200).build())) {
			cursor.forEachRemaining(keys::add);
		}
		return keys;
	}
}
