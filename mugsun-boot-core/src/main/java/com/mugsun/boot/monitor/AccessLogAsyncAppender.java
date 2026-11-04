package com.mugsun.boot.monitor;

import ch.qos.logback.classic.AsyncAppender;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.AsyncAppenderBase;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Field;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 访问日志异步写盘（#79）：队列满时最多等待 {@link #offerTimeoutMs}，仍写不进则丢弃并计数，
 * 请求线程<strong>不会</strong>无限期阻塞。
 * <p>标准 {@link AsyncAppender} 在 {@code neverBlock=false} 时对满队列调用
 * {@link BlockingQueue#put}，会一直等到有空位；{@code neverBlock=true} 则立刻
 * {@code offer} 失败即丢、不给短暂缓冲。本类在两者之间：有时限的 offer，超时丢弃。
 * <p>极端流量下优先不堵请求，可能丢少量访问日志；用限频 WARN 与
 * {@code mugsun.demo.access_log.dropped} 发现。
 * <p>生产与单测经反射读 {@link AsyncAppenderBase}{@code .blockingQueue}
 * （Spring Boot BOM 锁定 logback <strong>1.5.x</strong>）。升级 logback 若改掉该字段名，
 * {@link #start()} 解析失败会 {@code addError} 后 {@code stop}，即 refuse to start。
 */
public class AccessLogAsyncAppender extends AsyncAppender {

	static final long DEFAULT_OFFER_TIMEOUT_MS = 50L;
	static final long DEFAULT_DROP_WARN_INTERVAL_MS = 60_000L;

	private static final Logger LOG = LoggerFactory.getLogger(AccessLogAsyncAppender.class);

	private long offerTimeoutMs = DEFAULT_OFFER_TIMEOUT_MS;
	private long dropWarnIntervalMs = DEFAULT_DROP_WARN_INTERVAL_MS;

	/** 上次成功打出丢弃 WARN 的纳秒时间戳；0 表示尚未打过。 */
	private final AtomicLong lastWarnNanos = new AtomicLong();

	private volatile BlockingQueue<ILoggingEvent> queue;

	public long getOfferTimeoutMs() {
		return offerTimeoutMs;
	}

	public void setOfferTimeoutMs(long offerTimeoutMs) {
		this.offerTimeoutMs = Math.max(0L, offerTimeoutMs);
	}

	public long getDropWarnIntervalMs() {
		return dropWarnIntervalMs;
	}

	public void setDropWarnIntervalMs(long dropWarnIntervalMs) {
		this.dropWarnIntervalMs = Math.max(0L, dropWarnIntervalMs);
	}

	@Override
	public void start() {
		super.start();
		BlockingQueue<ILoggingEvent> q = resolveQueue();
		this.queue = q;
		if (q == null) {
			addError("AccessLogAsyncAppender: cannot resolve blockingQueue; refusing to start");
			super.stop();
		}
	}

	@Override
	protected void append(ILoggingEvent eventObject) {
		if (isQueueBelowDiscardingThreshold() && isDiscardable(eventObject)) {
			return;
		}
		try {
			preprocess(eventObject);
		} catch (RuntimeException ex) {
			// 不让预处理异常冒泡到 doAppend 被静默吞掉后既不入队也不计数
			addError("AccessLogAsyncAppender preprocess failed; dropping event", ex);
			onDrop();
			return;
		}
		if (!tryEnqueue(eventObject)) {
			onDrop();
		}
	}

	/**
	 * 有时限入队；超时、中断或队列未就绪返回 false（调用方应丢弃）。
	 * 包内可见便于单测直接灌满队列验证，不依赖 worker 时序。
	 */
	boolean tryEnqueue(ILoggingEvent eventObject) {
		BlockingQueue<ILoggingEvent> q = this.queue;
		if (q == null) {
			return false;
		}
		try {
			return q.offer(eventObject, offerTimeoutMs, TimeUnit.MILLISECONDS);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			return false;
		}
	}

	private void onDrop() {
		long total = AccessLogDropStats.DROPPED.incrementAndGet();
		long intervalNs = TimeUnit.MILLISECONDS.toNanos(dropWarnIntervalMs);
		long now = System.nanoTime();
		long prev = lastWarnNanos.get();
		boolean windowOpen = prev == 0L || now - prev >= intervalNs;
		if (windowOpen && lastWarnNanos.compareAndSet(prev, now)) {
			LOG.warn("[demo] 访问日志异步队列已满，已丢弃事件（累计丢弃 {} 条；"
					+ "offerTimeoutMs={}）。极端流量优先不堵请求，可能丢少量访问日志",
				total, offerTimeoutMs);
		}
	}

	/** 单测：替换入队队列（可灌满后断言超时丢弃）。 */
	void installQueueForTest(BlockingQueue<ILoggingEvent> queue) {
		this.queue = queue;
	}

	/** 单测：拨回限频窗口，使下一次丢弃可再打 WARN。 */
	void resetWarnWindowForTest() {
		lastWarnNanos.set(0L);
	}

	@SuppressWarnings("unchecked")
	private BlockingQueue<ILoggingEvent> resolveQueue() {
		try {
			Field f = AsyncAppenderBase.class.getDeclaredField("blockingQueue");
			f.setAccessible(true);
			Object v = f.get(this);
			if (v instanceof BlockingQueue<?> bq) {
				return (BlockingQueue<ILoggingEvent>) bq;
			}
			return null;
		} catch (ReflectiveOperationException e) {
			addError("AccessLogAsyncAppender: reflection on blockingQueue failed", e);
			return null;
		}
	}
}
