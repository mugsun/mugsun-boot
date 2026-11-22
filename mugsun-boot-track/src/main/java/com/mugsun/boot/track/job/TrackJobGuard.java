package com.mugsun.boot.track.job;

import com.mugsun.core.lock.template.LockHandle;
import com.mugsun.core.lock.template.LockTemplate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Optional;
import java.util.function.Supplier;

/**
 * 埋点调度任务的互斥（G88 LogCleanJob 同款范式）：多副本部署时同一任务只在一个节点执行，用通用的分布式锁（{@link LockTemplate}）。
 * <p>锁在任务执行期间自动续期；持锁节点宕机后没人续期，锁在一个租期（默认 30 秒）内自己放开。
 * <p>Redis 不可用时 fail-open 放行（rollup 全窗口幂等覆盖，并发重算结果一致，不构成正确性风险）。
 */
@Component
public class TrackJobGuard {

	private static final Logger log = LoggerFactory.getLogger(TrackJobGuard.class);

	private final LockTemplate lockTemplate;

	public TrackJobGuard(LockTemplate lockTemplate) {
		this.lockTemplate = lockTemplate;
	}

	/**
	 * 持锁执行动作；未获锁（他节点在跑）返回 {@code lockMissFallback} 跳过本轮。
	 *
	 * @param lockName 锁名，见 {@code TrackConstants.LOCK_*}
	 */
	public <T> T withLock(String lockName, Supplier<T> action, T lockMissFallback) {
		Optional<LockHandle> lock;
		try {
			lock = lockTemplate.tryLock(lockName);
		} catch (RuntimeException e) {
			// Redis 故障降级：直接执行（任务均为幂等重算，并发执行结果一致）
			log.warn("调度锁 Redis 不可用，本次放行执行（幂等任务无副作用）：{}", e.getMessage());
			return action.get();
		}
		if (lock.isEmpty()) {
			return lockMissFallback;
		}
		try (LockHandle ignored = lock.get()) {
			return action.get();
		}
	}
}
