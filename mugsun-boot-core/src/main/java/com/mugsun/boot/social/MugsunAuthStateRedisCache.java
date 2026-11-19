package com.mugsun.boot.social;

import me.zhyd.oauth.cache.AuthStateCache;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * JustAuth state 缓存的 Redis 实现：state 落 Redis（复用 G34 StringRedisTemplate），
 * 集群安全地防 CSRF/重放。替换 JustAuth 默认的进程内内存缓存。默认 3 分钟过期。
 * <p>JustAuth 只校验 state 存在，用途（登录还是谁的绑定）另存一个键，由 {@link #takePurpose} 取出即删。
 */
@Component
public class MugsunAuthStateRedisCache implements AuthStateCache {

	private static final String PREFIX = "mugsun:social:state:";
	private static final String PURPOSE_PREFIX = "mugsun:social:purpose:";
	private static final long DEFAULT_TIMEOUT_MS = Duration.ofMinutes(3).toMillis();
	/** GET 后 DEL 放在一个脚本里：并发的两次回调只有一次拿得到（不用 GETDEL，兼容 6.2 以前的 Redis） */
	private static final DefaultRedisScript<String> TAKE = new DefaultRedisScript<>(
		"local v = redis.call('GET', KEYS[1]) if v then redis.call('DEL', KEYS[1]) end return v", String.class);

	private final StringRedisTemplate redis;

	public MugsunAuthStateRedisCache(StringRedisTemplate redis) {
		this.redis = redis;
	}

	@Override
	public void cache(String key, String value) {
		cache(key, value, DEFAULT_TIMEOUT_MS);
	}

	@Override
	public void cache(String key, String value, long timeout) {
		redis.opsForValue().set(PREFIX + key, value, timeout, TimeUnit.MILLISECONDS);
	}

	@Override
	public String get(String key) {
		return redis.opsForValue().get(PREFIX + key);
	}

	@Override
	public boolean containsKey(String key) {
		return Boolean.TRUE.equals(redis.hasKey(PREFIX + key));
	}

	/** 记下 state 的用途，和 state 一样 3 分钟过期 */
	public void markPurpose(String state, String purpose) {
		redis.opsForValue().set(PURPOSE_PREFIX + state, purpose, DEFAULT_TIMEOUT_MS, TimeUnit.MILLISECONDS);
	}

	/** 取出并删除 state 的用途；过期、从没发过或已经用过都返回 null */
	public String takePurpose(String state) {
		return redis.execute(TAKE, List.of(PURPOSE_PREFIX + state));
	}

	/** 用过的 state 立即删掉（JustAuth 的 AuthStateCache 接口没有删除方法） */
	public void remove(String state) {
		redis.delete(PREFIX + state);
	}
}
