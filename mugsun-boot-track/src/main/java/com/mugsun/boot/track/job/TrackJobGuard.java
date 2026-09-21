package com.mugsun.boot.track.job;

import cn.hutool.core.util.IdUtil;
import com.mugsun.boot.common.constant.TrackConstants;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;
import java.util.function.Supplier;

/**
 * 埋点调度任务分布式锁（G88 LogCleanJob 同款范式）：Redis SETNX 持锁 + Lua compare-and-del 原子释放。
 * <p>多副本部署时同一任务只在一个节点执行；锁 TTL {@value TrackConstants#JOB_LOCK_SECONDS}s 防持锁节点宕机死锁。
 * <p>Redis 不可用时 fail-open 放行（rollup 全窗口幂等覆盖，并发重算结果一致，不构成正确性风险）。
 */
@Component
public class TrackJobGuard {

	private static final Logger log = LoggerFactory.getLogger(TrackJobGuard.class);

	/** Lua 原子释放：仅持锁者能删（TTL 过期被抢锁场景不误删他节点锁） */
	private static final String RELEASE_SCRIPT =
		"if redis.call('get', KEYS[1]) == ARGV[1] then return redis.call('del', KEYS[1]) else return 0 end";

	private final StringRedisTemplate redis;

	public TrackJobGuard(StringRedisTemplate redis) {
		this.redis = redis;
	}

	/**
	 * 持锁执行动作；未获锁（他节点在跑）返回 {@code lockMissFallback} 跳过本轮。
	 */
	public <T> T withLock(String lockKey, Supplier<T> action, T lockMissFallback) {
		String token = IdUtil.fastSimpleUUID();
		Boolean acquired;
		try {
			acquired = redis.opsForValue().setIfAbsent(lockKey, token, Duration.ofSeconds(TrackConstants.JOB_LOCK_SECONDS));
		} catch (RuntimeException e) {
			// Redis 故障降级：直接执行（任务均为幂等重算，并发执行结果一致）
			log.warn("调度锁 Redis 不可用，本次放行执行（幂等任务无副作用）：{}", e.getMessage());
			acquired = Boolean.TRUE;
		}
		if (!Boolean.TRUE.equals(acquired)) {
			return lockMissFallback;
		}
		try {
			return action.get();
		} finally {
			try {
				redis.execute(new DefaultRedisScript<>(RELEASE_SCRIPT, Long.class), List.of(lockKey), token);
			} catch (RuntimeException e) {
				log.warn("调度锁释放失败（等 TTL 自然过期）：{}", e.getMessage());
			}
		}
	}
}
