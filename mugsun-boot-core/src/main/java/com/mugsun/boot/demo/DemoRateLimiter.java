package com.mugsun.boot.demo;

import io.github.bucket4j.Bandwidth;
import io.github.bucket4j.BucketConfiguration;
import io.github.bucket4j.distributed.ExpirationAfterWriteStrategy;
import io.github.bucket4j.distributed.proxy.ProxyManager;
import io.github.bucket4j.redis.lettuce.Bucket4jLettuce;
import io.lettuce.core.AbstractRedisClient;
import io.lettuce.core.RedisClient;
import io.lettuce.core.api.StatefulConnection;
import io.lettuce.core.cluster.RedisClusterClient;
import io.lettuce.core.cluster.api.StatefulRedisClusterConnection;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.codec.ByteArrayCodec;
import io.lettuce.core.codec.RedisCodec;
import io.lettuce.core.codec.StringCodec;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * 只读演示模式的按 IP 限流：Bucket4j + Redis（Lettuce）后端，多实例共享同一组令牌桶。
 * 桶键 {@code mugsun:demo:rl:<scope>:<ip>}，在 demo 专用的 Redis db 里，按补满时间自动过期。
 * 运行中 Redis 不可用时放行并记 WARN——限流失效不能把浏览打断（登录本身依赖 Redis 会失败）；启动时连不上 Redis 则 demo 起不来。
 */
@Component
@ConditionalOnProperty(name = DemoConstants.ENABLED_PROPERTY, havingValue = "true")
public class DemoRateLimiter {

	private static final Logger log = LoggerFactory.getLogger(DemoRateLimiter.class);
	private static final String KEY_PREFIX = "mugsun:demo:rl:";

	/** 限流维度 */
	public enum Scope { LOGIN, TRACK }

	private final StatefulConnection<String, byte[]> connection;
	private final ProxyManager<String> proxyManager;
	private final BucketConfiguration loginConfig;
	private final BucketConfiguration trackConfig;

	public DemoRateLimiter(LettuceConnectionFactory connectionFactory,
						   @Value("${mugsun.demo.rate.login-per-ip-per-minute:20}") int loginPerMinute,
						   @Value("${mugsun.demo.rate.track-per-ip-per-minute:120}") int trackPerMinute) {
		// 复用 Spring 配好的 Lettuce 客户端：spring.data.redis 的 url / host / sentinel / cluster / ssl / db 都按同一份配置生效。
		// 客户端归 Spring 管，这里只开一条自己的连接（String 键 + byte[] 值编解码），销毁时只关这条连接。
		RedisCodec<String, byte[]> codec = RedisCodec.of(StringCodec.UTF8, ByteArrayCodec.INSTANCE);
		AbstractRedisClient nativeClient = connectionFactory.getNativeClient();
		if (nativeClient instanceof RedisClusterClient cluster) {
			StatefulRedisClusterConnection<String, byte[]> c = cluster.connect(codec);
			this.connection = c;
			this.proxyManager = Bucket4jLettuce.casBasedBuilder(c)
				.expirationAfterWrite(ExpirationAfterWriteStrategy.basedOnTimeForRefillingBucketUpToMax(Duration.ofMinutes(1)))
				.build();
		} else if (nativeClient instanceof RedisClient client) {
			StatefulRedisConnection<String, byte[]> c = client.connect(codec);
			this.connection = c;
			this.proxyManager = Bucket4jLettuce.casBasedBuilder(c)
				.expirationAfterWrite(ExpirationAfterWriteStrategy.basedOnTimeForRefillingBucketUpToMax(Duration.ofMinutes(1)))
				.build();
		} else {
			throw new IllegalStateException("[demo] 不支持的 Redis 客户端类型：" + nativeClient);
		}
		this.loginConfig = perMinute(loginPerMinute);
		this.trackConfig = perMinute(trackPerMinute);
	}

	private static BucketConfiguration perMinute(int n) {
		int capacity = Math.max(1, n);
		return BucketConfiguration.builder()
			.addLimit(Bandwidth.builder().capacity(capacity).refillGreedy(capacity, Duration.ofMinutes(1)).build())
			.build();
	}

	/** 取一个令牌；false = 该 IP 本分钟已超额 */
	public boolean tryAcquire(Scope scope, String ip) {
		String key = KEY_PREFIX + scope.name().toLowerCase() + ":" + (ip == null || ip.isEmpty() ? "-" : ip);
		BucketConfiguration config = scope == Scope.LOGIN ? loginConfig : trackConfig;
		try {
			return proxyManager.builder().build(key, () -> config).tryConsume(1);
		} catch (RuntimeException e) {
			log.warn("[demo] 限流 Redis 不可用，本次放行: {}", e.toString());
			return true;
		}
	}

	@PreDestroy
	void close() {
		connection.close();
	}
}
