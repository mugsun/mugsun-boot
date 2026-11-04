package com.mugsun.boot.monitor;

import java.util.concurrent.atomic.AtomicLong;

/**
 * 访问日志异步队列丢弃计数（#79）。
 * <p>Logback appender 与 Spring Micrometer 不在同一生命周期里装配：用进程内共享
 * {@link AtomicLong} 桥接，请求路径只做 {@code incrementAndGet}，不碰 MeterRegistry。
 */
public final class AccessLogDropStats {

	/** Micrometer {@code mugsun.demo.access_log.dropped} 与 appender 共用。 */
	static final AtomicLong DROPPED = new AtomicLong();

	private AccessLogDropStats() {
	}

	/** 累计丢弃条数（含尚未打出限频 WARN 的）。 */
	public static long dropped() {
		return DROPPED.get();
	}

	/** 单测隔离：清零累计。 */
	static void resetForTest() {
		DROPPED.set(0);
	}
}
