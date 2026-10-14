package com.mugsun.boot.demo;

import io.github.bucket4j.Bandwidth;
import io.github.bucket4j.BucketConfiguration;
import io.github.bucket4j.distributed.ExpirationAfterWriteStrategy;
import io.github.bucket4j.distributed.proxy.ProxyManager;
import io.github.bucket4j.redis.lettuce.Bucket4jLettuce;
import io.lettuce.core.RedisClient;
import io.lettuce.core.RedisURI;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.codec.ByteArrayCodec;
import io.lettuce.core.codec.RedisCodec;
import io.lettuce.core.codec.StringCodec;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.data.redis.RedisProperties;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * 只读演示模式的按 IP 限流：Bucket4j + Redis（Lettuce）后端，多实例共享同一组令牌桶。
 * 桶键 {@code mugsun:demo:rl:<scope>:<ip>}，在 demo 专用的 Redis db 里，按补满时间自动过期。
 * Redis 不可用时放行并记 WARN——限流失效不能把登录或浏览打断。
 */
@Component
@ConditionalOnProperty(name = DemoConstants.ENABLED_PROPERTY, havingValue = "true")
public class DemoRateLimiter {

	private static final Logger log = LoggerFactory.getLogger(DemoRateLimiter.class);
	private static final String KEY_PREFIX = "mugsun:demo:rl:";

	/** 限流维度 */
	public enum Scope { LOGIN, TRACK }

	private final RedisClient client;
	private final StatefulRedisConnection<String, byte[]> connection;
	private final ProxyManager<String> proxyManager;
	private final BucketConfiguration loginConfig;
	private final BucketConfiguration trackConfig;

	public DemoRateLimiter(RedisProperties redis,
						   @Value("${mugsun.demo.rate.login-per-ip-per-minute:20}") int loginPerMinute,
						   @Value("${mugsun.demo.rate.track-per-ip-per-minute:120}") int trackPerMinute) {
		RedisURI.Builder uri = RedisURI.builder()
			.withHost(redis.getHost())
			.withPort(redis.getPort())
			.withDatabase(redis.getDatabase())
			.withSsl(redis.getSsl() != null && redis.getSsl().isEnabled())
			.withTimeout(redis.getTimeout() == null ? Duration.ofSeconds(3) : redis.getTimeout());
		if (redis.getPassword() != null && !redis.getPassword().isEmpty()) {
			if (redis.getUsername() != null && !redis.getUsername().isEmpty()) {
				uri.withAuthentication(redis.getUsername(), redis.getPassword());
			} else {
				uri.withPassword(redis.getPassword().toCharArray());
			}
		}
		this.client = RedisClient.create(uri.build());
		this.connection = client.connect(RedisCodec.of(StringCodec.UTF8, ByteArrayCodec.INSTANCE));
		this.proxyManager = Bucket4jLettuce.casBasedBuilder(connection)
			.expirationAfterWrite(ExpirationAfterWriteStrategy.basedOnTimeForRefillingBucketUpToMax(Duration.ofMinutes(1)))
			.build();
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
		client.shutdown();
	}
}
