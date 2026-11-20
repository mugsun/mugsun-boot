package com.mugsun.boot.cache;

import com.alicp.jetcache.CacheValueHolder;
import com.alicp.jetcache.support.CacheEncodeException;
import com.alicp.jetcache.support.CacheMessage;
import com.alicp.jetcache.support.JavaValueEncoder;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 远程缓存的 JSON 编解码必须「写得进也读得出」。
 * 库自带的 INSTANCE 写入带编号、读取却查不到解码器，远程命中与失效广播全部失败，且只在日志里报错。
 */
class JetCacheJsonCodecConfigTest {

	private final JetCacheJsonCodecConfig config = new JetCacheJsonCodecConfig();

	private final Function<Object, byte[]> encoder = config.jsonValueEncoder();

	private final Function<byte[], Object> decoder = config.jsonValueDecoder();

	@Test
	@DisplayName("缓存值编码后能原样读回")
	void valueHolderRoundTrips() {
		CacheValueHolder<String> holder = new CacheValueHolder<>("v1", 60_000);

		Object decoded = decoder.apply(encoder.apply(holder));

		assertThat(decoded).isInstanceOf(CacheValueHolder.class);
		CacheValueHolder<?> back = (CacheValueHolder<?>) decoded;
		assertThat(back.getValue()).isEqualTo("v1");
		assertThat(back.getExpireTime()).isEqualTo(holder.getExpireTime());
	}

	@Test
	@DisplayName("失效广播消息编码后能读回，键与类型不丢")
	void broadcastMessageRoundTrips() {
		CacheMessage msg = new CacheMessage();
		msg.setSourceId("node-a");
		msg.setArea("default");
		msg.setCacheName("param:");
		msg.setType(CacheMessage.TYPE_REMOVE);
		msg.setKeys(new Object[] { "sys.title", "sys.logo" });

		Object decoded = decoder.apply(encoder.apply(msg));

		assertThat(decoded).isInstanceOf(CacheMessage.class);
		CacheMessage back = (CacheMessage) decoded;
		assertThat(back.getSourceId()).isEqualTo("node-a");
		assertThat(back.getCacheName()).isEqualTo("param:");
		assertThat(back.getType()).isEqualTo(CacheMessage.TYPE_REMOVE);
		assertThat(back.getKeys()).containsExactly("sys.title", "sys.logo");
	}

	@Test
	@DisplayName("带 Java 序列化编号的数据不会被还原成对象")
	void javaSerializedPayloadIsRejected() {
		byte[] javaBytes = JavaValueEncoder.INSTANCE.apply(new CacheValueHolder<>("x", 60_000));

		assertThatThrownBy(() -> decoder.apply(javaBytes)).isInstanceOf(CacheEncodeException.class);
	}
}
