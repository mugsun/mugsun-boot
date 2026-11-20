package com.mugsun.boot.cache;

import com.alicp.jetcache.support.Fastjson2ValueDecoder;
import com.alicp.jetcache.support.Fastjson2ValueEncoder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * JetCache 2.7.9 内置 valueEncoder 只有 java / kryo / kryo5，没有 jackson 字符串。
 * 用库自带 Fastjson2 JSON 编解码替代 JDK 序列化（对齐「缓存层非 Java 序列化」）。
 *
 * <p>不用库里的 {@code INSTANCE}：它在数据前写 4 字节编码器编号，读取时按编号查 {@code DecoderMap}，
 * 而 2.7.9 的默认表只登记了 java / kryo，没有 Fastjson2——远程缓存写得进、读不出
 * （no decoder for identity number），失效广播也解不开。不带编号的实例读写固定走 JSON，
 * 也不会被带 java 编号的数据引到 JDK 反序列化上。
 */
@Configuration
public class JetCacheJsonCodecConfig {

	@Bean(name = "jsonValueEncoder")
	public Fastjson2ValueEncoder jsonValueEncoder() {
		return new Fastjson2ValueEncoder(false);
	}

	@Bean(name = "jsonValueDecoder")
	public Fastjson2ValueDecoder jsonValueDecoder() {
		return new Fastjson2ValueDecoder(false);
	}
}
