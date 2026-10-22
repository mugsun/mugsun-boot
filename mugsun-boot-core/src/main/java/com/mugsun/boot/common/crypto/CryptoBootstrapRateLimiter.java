package com.mugsun.boot.common.crypto;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 接口密钥启动下发的按用户限流：Redis 固定窗口（每用户每分钟 {@code per-user-per-minute} 次，默认 30），多实例共享。
 * <p>INCR 与设过期在一段 Lua 里原子完成；计数键没有过期（TTL=-1，例如上次设过期失败）时也补设，
 * 不会留下永不过期、把用户永久锁住的计数。
 * <p>Redis 出错时放行并打 WARN：下发本身要求已登录，限流失效不能让前端拿不到密钥。
 */
@Component
public class CryptoBootstrapRateLimiter {

	private static final Logger log = LoggerFactory.getLogger(CryptoBootstrapRateLimiter.class);
	public static final String KEY_PREFIX = "mugsun:crypto:bootstrap:rl:";
	static final long WINDOW_SECONDS = 60;

	/** KEYS[1]=计数键，ARGV[1]=窗口秒数；返回计数 */
	static final RedisScript<Long> SCRIPT = new DefaultRedisScript<>(
		"local n = redis.call('INCR', KEYS[1]) "
			+ "if n == 1 or redis.call('TTL', KEYS[1]) == -1 then redis.call('EXPIRE', KEYS[1], ARGV[1]) end "
			+ "return n", Long.class);

	private final StringRedisTemplate redis;
	private final int perMinute;

	public CryptoBootstrapRateLimiter(StringRedisTemplate redis,
									  @Value("${mugsun.crypto.bootstrap.per-user-per-minute:30}") int perMinute) {
		this.redis = redis;
		this.perMinute = Math.max(1, perMinute);
	}

	public boolean tryAcquire(String userId) {
		try {
			Long n = redis.execute(SCRIPT, List.of(KEY_PREFIX + userId), String.valueOf(WINDOW_SECONDS));
			return n == null || n <= perMinute;
		} catch (RuntimeException e) {
			log.warn("接口密钥下发限流读写 Redis 失败，本次放行：{}", e.getClass().getSimpleName());
			return true;
		}
	}
}
