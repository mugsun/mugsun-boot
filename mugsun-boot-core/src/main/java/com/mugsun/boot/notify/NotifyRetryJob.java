package com.mugsun.boot.notify;

import cn.hutool.core.util.IdUtil;
import com.mugsun.boot.common.constant.NotifyConstants;
import com.mugsun.boot.notify.entity.SysNotifyRecord;
import com.mugsun.boot.notify.mapper.SysNotifyRecordMapper;
import com.mugsun.boot.system.service.ParamService;
import com.mugsun.boot.tenant.TenantContext;
import com.mybatisflex.core.query.QueryWrapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 失败重试调度：定时扫描 FAILURE 且到期的发送流水重发（非 @Async 发一次）。
 * <p>全库首个 @Scheduled 调度（裁定引入）：固定 15s tick，实际扫描按 sys_param
 * {@code notify.retry.scan-interval-ms} 节流（默认 60s，代码常量兜底）；
 * 集群安全用 Redis SETNX 分布式锁包裹扫描（参照 LoginLockService 的 Redis 原子操作范式），
 * 扫描过程无 ThreadUtil.sleep 之类的阻塞，扫到即走。
 */
@Component
public class NotifyRetryJob {

	private static final Logger log = LoggerFactory.getLogger(NotifyRetryJob.class);

	private final SysNotifyRecordMapper recordMapper;
	private final NotifyDispatcher dispatcher;
	private final ParamService paramService;
	private final StringRedisTemplate redis;

	/** 上次扫描时间戳（本节点内存节流；集群多节点各自节流 + 分布式锁兜底） */
	private volatile long lastScanAt;

	public NotifyRetryJob(SysNotifyRecordMapper recordMapper, NotifyDispatcher dispatcher,
						  ParamService paramService, StringRedisTemplate redis) {
		this.recordMapper = recordMapper;
		this.dispatcher = dispatcher;
		this.paramService = paramService;
		this.redis = redis;
	}

	/** 固定 tick：到间隔才扫描（间隔走 sys_param 热生效） */
	@Scheduled(fixedDelay = NotifyConstants.RETRY_SCHEDULE_TICK_MS)
	public void tick() {
		long interval = scanIntervalMs();
		long now = System.currentTimeMillis();
		if (now - lastScanAt < interval) {
			return;
		}
		lastScanAt = now;
		scanAndRetry();
	}

	/** 扫描并重试到期流水（package-private 供集成测试直接触发，避免长时间 sleep 等待调度）。
	 *  锁预算：批次 × 单条投递超时的最坏耗时须远小于锁 TTL（两节点并发重发同一条的窗口），超时即截断本批。 */
	void scanAndRetry() {
		String token = IdUtil.fastSimpleUUID();
		Boolean acquired = redis.opsForValue().setIfAbsent(NotifyConstants.RETRY_LOCK_KEY, token,
			Duration.ofSeconds(NotifyConstants.RETRY_LOCK_SECONDS));
		if (!Boolean.TRUE.equals(acquired)) {
			return;
		}
		long startedAt = System.currentTimeMillis();
		// 预算取锁 TTL 的 1/3：持锁超时前主动收手，剩余批次留给下一 tick/其他节点
		long budgetMs = NotifyConstants.RETRY_LOCK_SECONDS * 1000L / 3;
		try {
			int maxTimes = intParam(NotifyConstants.PARAM_RETRY_MAX_TIMES, NotifyConstants.DEFAULT_RETRY_MAX_TIMES);
			long backoffMs = longParam(NotifyConstants.PARAM_RETRY_BACKOFF, NotifyConstants.DEFAULT_RETRY_BACKOFF_MS);
			// 流水租户隔离：扫描须跨租户全量（唯一忽略入口），逐条重放其自身租户上下文重发
			List<SysNotifyRecord> due = TenantContext.ignore(() -> recordMapper.selectListByQuery(
				QueryWrapper.create()
					.eq("status", NotifyConstants.STATUS_FAILURE)
					.le("next_retry_time", LocalDateTime.now())
					.lt("retry_count", maxTimes)
					.orderBy("id", true)
					.limit(NotifyConstants.RETRY_SCAN_BATCH_SIZE)));
			for (SysNotifyRecord record : due) {
				if (System.currentTimeMillis() - startedAt > budgetMs) {
					log.warn("通知重试批次超锁预算截断，剩余 {} 条留待下轮", due.size() - due.indexOf(record));
					break;
				}
				try {
					NotifyDispatcher.inTenant(record.getTenantId(),
						() -> dispatcher.retryOne(record, maxTimes, backoffMs));
				} catch (Exception e) {
					log.error("通知重试异常 recordId={}", record.getId(), e);
				}
			}
			if (!due.isEmpty()) {
				log.info("通知重试扫描完成，处理 {} 条", due.size());
			}
		} finally {
			// Lua compare-and-del 原子释放：TTL 过期被抢锁场景不误删他节点锁
			String script = "if redis.call('get', KEYS[1]) == ARGV[1] then return redis.call('del', KEYS[1]) else return 0 end";
			redis.execute(new org.springframework.data.redis.core.script.DefaultRedisScript<>(script, Long.class),
				java.util.List.of(NotifyConstants.RETRY_LOCK_KEY), token);
		}
	}

	private long scanIntervalMs() {
		return longParam(NotifyConstants.PARAM_RETRY_SCAN_INTERVAL, NotifyConstants.DEFAULT_RETRY_SCAN_INTERVAL_MS);
	}

	private int intParam(String key, int fallback) {
		String value = paramService.getValue(key);
		try {
			return value == null ? fallback : Integer.parseInt(value.trim());
		} catch (NumberFormatException e) {
			return fallback;
		}
	}

	private long longParam(String key, long fallback) {
		String value = paramService.getValue(key);
		try {
			return value == null ? fallback : Long.parseLong(value.trim());
		} catch (NumberFormatException e) {
			return fallback;
		}
	}
}
