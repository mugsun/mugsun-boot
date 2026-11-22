package com.mugsun.boot.notify;

import com.mugsun.boot.demo.ConditionalOnNotDemo;

import com.mugsun.boot.common.constant.NotifyConstants;
import com.mugsun.boot.notify.entity.SysNotifyRecord;
import com.mugsun.boot.notify.mapper.SysNotifyRecordMapper;
import com.mugsun.boot.system.service.ParamService;
import com.mugsun.boot.tenant.TenantContext;
import com.mugsun.core.lock.template.LockHandle;
import com.mugsun.core.lock.template.LockTemplate;
import com.mybatisflex.core.query.QueryWrapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * 失败重试调度：定时扫描 FAILURE 且到期的发送流水重发（非 @Async 发一次）。
 * <p>全库首个 @Scheduled 调度（裁定引入）：固定 15s tick，实际扫描按 sys_param
 * {@code notify.retry.scan-interval-ms} 节流（默认 60s，代码常量兜底）；
 * 集群安全用分布式锁（{@link LockTemplate}）包裹扫描，同一时刻只有一个节点在扫，
 * 扫描过程无 ThreadUtil.sleep 之类的阻塞，扫到即走。
 */
@Component
@ConditionalOnNotDemo
public class NotifyRetryJob {

	private static final Logger log = LoggerFactory.getLogger(NotifyRetryJob.class);

	private final SysNotifyRecordMapper recordMapper;
	private final NotifyDispatcher dispatcher;
	private final ParamService paramService;
	private final LockTemplate lockTemplate;

	/** 上次扫描时间戳（本节点内存节流；集群多节点各自节流 + 分布式锁兜底） */
	private volatile long lastScanAt;

	public NotifyRetryJob(SysNotifyRecordMapper recordMapper, NotifyDispatcher dispatcher,
						  ParamService paramService, LockTemplate lockTemplate) {
		this.recordMapper = recordMapper;
		this.dispatcher = dispatcher;
		this.paramService = paramService;
		this.lockTemplate = lockTemplate;
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
	 *  别的节点正在扫时本轮跳过；锁在扫描期间自动续期。一轮有时间上限，超时即截断本批。 */
	void scanAndRetry() {
		Optional<LockHandle> lock = lockTemplate.tryLock(NotifyConstants.RETRY_LOCK_NAME);
		if (lock.isEmpty()) {
			return;
		}
		long startedAt = System.currentTimeMillis();
		// 一轮最多跑这么久：到点主动收手，剩余批次留给下一 tick/其他节点
		long budgetMs = NotifyConstants.RETRY_ROUND_BUDGET_MS;
		try (LockHandle ignored = lock.get()) {
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
					log.warn("通知重试本轮超过时间上限，已截断，剩余 {} 条留待下轮", due.size() - due.indexOf(record));
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
