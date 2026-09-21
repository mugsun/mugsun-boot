package com.mugsun.boot.cache;

import com.alicp.jetcache.support.Fastjson2ValueDecoder;
import com.alicp.jetcache.support.Fastjson2ValueEncoder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * JetCache 2.7.9 内置 valueEncoder 只有 java / kryo / kryo5，没有 jackson 字符串。
 * 用库自带 Fastjson2 JSON 编解码替代 JDK 序列化（对齐「缓存层非 Java 序列化」）。
 */
@Configuration
public class JetCacheJsonCodecConfig {

	@Bean(name = "jsonValueEncoder")
	public Fastjson2ValueEncoder jsonValueEncoder() {
		return Fastjson2ValueEncoder.INSTANCE;
	}

	@Bean(name = "jsonValueDecoder")
	public Fastjson2ValueDecoder jsonValueDecoder() {
		return Fastjson2ValueDecoder.INSTANCE;
	}
}
