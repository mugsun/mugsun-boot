package com.mugsun.boot.track;

import com.mugsun.boot.common.constant.TrackConstants;
import com.mugsun.boot.tenant.TenantContext;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.dromara.x.file.storage.core.FileInfo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * 回放块异步消费器（{@link TrackEventConsumer} 同款范式）：内存有界队列 → 逐块落对象存储 + 元数据 upsert。
 * <p><b>执行器纪律</b>：独立 {@code newVirtualThreadPerTaskExecutor}，不走 @Async、不挂 TenantTaskDecorator——
 * 消费跨租户混合，严禁继承发起请求的租户上下文；每行 tenant_id 以块自带值显式写，
 * 落库全程 {@link TenantContext#ignore} + {@link TrackDS}（在 {@link TrackReplayStore} 类级）包裹。
 * <p><b>逐块处理不攒批</b>：回放量级远低于事件流（场景 B 约 0.1 块/秒），单线程保会话内块按到达序落储，
 * 首块建行语义（start_time/存储坐标取首块）因此确定。
 * <p><b>失败重试</b>：块重回队列尾，指数退避（1s 基数翻倍、30s 封顶），超 {@value TrackConstants#CONSUME_MAX_RETRY}
 * 次丢弃 + 计数；对象键确定（同 session+seq 重写即覆盖）+ 会话置位 GREATEST 幂等，重试安全
 * （元数据累加列依赖「置位在前、upsert 在后且只执行一次」的顺序保证，见 {@link TrackReplayStore#persistBlock}）。
 * <p><b>背压</b>：队列满 offer 失败 = 丢新（摄入侧已删幂等键并回 503，SDK 会重发整块）。
 */
@Component
public class TrackReplayConsumer {

	private static final Logger log = LoggerFactory.getLogger(TrackReplayConsumer.class);

	private final LinkedBlockingQueue<TrackReplayBlock> queue =
		new LinkedBlockingQueue<>(TrackConstants.REPLAY_QUEUE_CAPACITY);
	/** 独立虚拟线程执行器：不挂 TenantTaskDecorator（见类 javadoc 执行器纪律） */
	private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
	private volatile boolean running = true;

	private final TrackReplayStorage storage;
	private final TrackReplayStore store;
	private final TrackIngestMetrics metrics;

	public TrackReplayConsumer(TrackReplayStorage storage, TrackReplayStore store, TrackIngestMetrics metrics) {
		this.storage = storage;
		this.store = store;
		this.metrics = metrics;
	}

	/** 启动消费循环（单线程，{@value TrackConstants#REPLAY_CONSUME_THREAD_COUNT}；不占业务连接池——落库走 track 池） */
	@PostConstruct
	void start() {
		for (int i = 0; i < TrackConstants.REPLAY_CONSUME_THREAD_COUNT; i++) {
			executor.submit(this::consumeLoop);
		}
	}

	/** 摄入侧入队（满即 false=丢新，由调用方删幂等键 + 回 503 + 计数） */
	public boolean offer(TrackReplayBlock block) {
		return queue.offer(block);
	}

	/** 消费循环：poll（500ms 空转 tick）→ 逐块落储；中断即退出 */
	private void consumeLoop() {
		while (running) {
			TrackReplayBlock block = null;
			try {
				block = queue.poll(TrackConstants.CONSUME_BATCH_WINDOW_MS, TimeUnit.MILLISECONDS);
				if (block == null) {
					continue;
				}
				flush(block);
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
				return;
			} catch (Exception e) {
				// flush 已自处理重试；此处为兜底防御（如 NPE），块重回队列尾
				log.error("回放块消费异常，块重回队列：{}", e.getMessage(), e);
				if (block != null) {
					requeue(block);
				}
			}
		}
	}

	/** 落一块：对象存储写入（键确定，覆盖幂等）→ 会话快照 → 置位 + 元数据 upsert（同队列序即同会话块序） */
	private void flush(TrackReplayBlock block) {
		try {
			FileInfo stored = storage.save(block);
			// 跨租户混合写：显式 ignore 租户上下文（原生 SQL 本不过租户插件，双保险）
			TrackReplayStore.SessionSnapshot snapshot = TenantContext.ignore(() -> {
				TrackReplayStore.SessionSnapshot snap = store.snapshotOfSession(block.getSessionId());
				store.persistBlock(block, stored, snap);
				return snap;
			});
		} catch (Exception e) {
			log.warn("回放块落储失败（块重回队列尾重试）session={} seq={}：{}", block.getSessionId(), block.getSeq(), e.getMessage());
			requeue(block);
		}
	}

	/** 块重回队列尾：attempts+1，指数退避后经独立虚拟线程 re-offer；超上限丢弃 + 计数（at-most-once 语义） */
	private void requeue(TrackReplayBlock block) {
		int attempts = block.getAttempts() + 1;
		block.setAttempts(attempts);
		if (attempts > TrackConstants.CONSUME_MAX_RETRY) {
			metrics.dropped("replay_retry_exhausted", 1);
			return;
		}
		long backoff = Math.min(TrackConstants.CONSUME_RETRY_BACKOFF_BASE_MS << (attempts - 1),
			TrackConstants.CONSUME_RETRY_BACKOFF_MAX_MS);
		try {
			executor.submit(() -> {
				try {
					Thread.sleep(backoff);
				} catch (InterruptedException e) {
					Thread.currentThread().interrupt();
					return;
				}
				if (!queue.offer(block)) {
					metrics.dropped("replay_queue_full", 1);
				}
			});
		} catch (java.util.concurrent.RejectedExecutionException e) {
			// 停机窗口：执行器已关，按 at-most-once 语义丢弃 + 计数
			metrics.dropped("replay_persist_failed", 1);
		}
	}

	/** 停机：退出循环 → 等 in-flight → 残余队列尽力逐块 flush 一次（at-most-once 语义内的最后努力） */
	@PreDestroy
	void stop() {
		running = false;
		executor.shutdown();
		try {
			if (!executor.awaitTermination(3, TimeUnit.SECONDS)) {
				executor.shutdownNow();
			}
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			executor.shutdownNow();
		}
		List<TrackReplayBlock> rest = new ArrayList<>();
		queue.drainTo(rest);
		for (TrackReplayBlock block : rest) {
			try {
				flush(block);
			} catch (Exception e) {
				log.warn("停机残余回放块落储失败，按 at-most-once 语义丢弃 session={} seq={}：{}",
					block.getSessionId(), block.getSeq(), e.getMessage());
			}
		}
	}
}
