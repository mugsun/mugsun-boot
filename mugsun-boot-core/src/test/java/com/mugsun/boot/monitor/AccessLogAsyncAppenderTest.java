package com.mugsun.boot.monitor;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.LoggingEvent;
import ch.qos.logback.core.AppenderBase;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * #79：队列满 → 等待 offerTimeout → 丢弃 + 计数；WARN 限频；入队线程不无限阻塞。
 */
class AccessLogAsyncAppenderTest {

	private AccessLogAsyncAppender appender;
	private ListAppender<ILoggingEvent> warnProbe;
	private Logger warnLogger;

	@BeforeEach
	void setUp() {
		AccessLogDropStats.resetForTest();
		appender = new AccessLogAsyncAppender();
		appender.setContext(new LoggerContext());
		appender.setName("ACCESS_ASYNC_TEST");
		appender.setQueueSize(1);
		appender.setDiscardingThreshold(0);
		appender.setNeverBlock(false);
		appender.setOfferTimeoutMs(50);
		appender.setDropWarnIntervalMs(60_000);
		appender.setIncludeCallerData(false);

		AppenderBase<ILoggingEvent> nop = new AppenderBase<>() {
			@Override
			protected void append(ILoggingEvent eventObject) {
				// no-op：入队行为由 installQueueForTest 控制
			}
		};
		nop.setName("NOP");
		nop.setContext(appender.getContext());
		nop.start();
		appender.addAppender(nop);
		appender.start();
		assertThat(appender.isStarted()).isTrue();

		warnLogger = (Logger) LoggerFactory.getLogger(AccessLogAsyncAppender.class);
		warnProbe = new ListAppender<>();
		warnProbe.start();
		warnLogger.addAppender(warnProbe);
		warnLogger.setLevel(Level.WARN);
	}

	@AfterEach
	void tearDown() {
		if (warnLogger != null && warnProbe != null) {
			warnLogger.detachAppender(warnProbe);
		}
		if (appender != null && appender.isStarted()) {
			appender.stop();
		}
		AccessLogDropStats.resetForTest();
	}

	@Test
	void fullQueueWaitsThenDropsAndCounts() {
		BlockingQueue<ILoggingEvent> full = new ArrayBlockingQueue<>(1);
		assertThat(full.offer(event("pad"))).isTrue();
		appender.installQueueForTest(full);

		long before = AccessLogDropStats.dropped();
		long t0 = System.nanoTime();
		appender.doAppend(event("should-drop"));
		long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - t0);

		assertThat(AccessLogDropStats.dropped()).isEqualTo(before + 1);
		assertThat(elapsedMs)
			.as("should wait about offerTimeoutMs then return, never hang")
			.isBetween(30L, 500L);
		assertThat(warnProbe.list).hasSize(1);
		assertThat(warnProbe.list.get(0).getFormattedMessage()).contains("累计丢弃");
	}

	@Test
	void dropWarnIsRateLimitedWithinWindow() {
		BlockingQueue<ILoggingEvent> full = new ArrayBlockingQueue<>(1);
		assertThat(full.offer(event("pad"))).isTrue();
		appender.installQueueForTest(full);
		appender.setDropWarnIntervalMs(60_000);

		for (int i = 0; i < 5; i++) {
			appender.doAppend(event("drop-" + i));
		}
		assertThat(AccessLogDropStats.dropped()).isEqualTo(5);
		assertThat(warnProbe.list).hasSize(1);

		appender.resetWarnWindowForTest();
		appender.doAppend(event("drop-again"));
		assertThat(AccessLogDropStats.dropped()).isEqualTo(6);
		assertThat(warnProbe.list).hasSize(2);
	}

	@Test
	void interruptedOfferDropsAndReturns() throws Exception {
		BlockingQueue<ILoggingEvent> full = new ArrayBlockingQueue<>(1);
		assertThat(full.offer(event("pad"))).isTrue();
		appender.installQueueForTest(full);
		appender.setOfferTimeoutMs(5_000);

		AtomicInteger droppedDelta = new AtomicInteger();
		CountDownLatch started = new CountDownLatch(1);
		Thread t = new Thread(() -> {
			started.countDown();
			long before = AccessLogDropStats.dropped();
			appender.doAppend(event("interrupt-me"));
			droppedDelta.set((int) (AccessLogDropStats.dropped() - before));
		}, "access-log-offer-interrupt");
		t.start();
		assertThat(started.await(1, TimeUnit.SECONDS)).isTrue();
		Thread.sleep(30);
		t.interrupt();
		t.join(2_000);
		assertThat(t.isAlive()).isFalse();
		assertThat(droppedDelta.get()).isEqualTo(1);
	}

	@Test
	void endToEndWorkerBlockedQueueDrops() throws Exception {
		AccessLogDropStats.resetForTest();
		CountDownLatch release = new CountDownLatch(1);
		AccessLogAsyncAppender live = new AccessLogAsyncAppender();
		live.setContext((LoggerContext) LoggerFactory.getILoggerFactory());
		live.setName("LIVE_ACCESS_ASYNC");
		live.setQueueSize(1);
		live.setDiscardingThreshold(0);
		live.setOfferTimeoutMs(50);
		live.setDropWarnIntervalMs(60_000);
		AppenderBase<ILoggingEvent> blocking = new AppenderBase<>() {
			@Override
			protected void append(ILoggingEvent eventObject) {
				try {
					release.await(30, TimeUnit.SECONDS);
				} catch (InterruptedException e) {
					Thread.currentThread().interrupt();
				}
			}
		};
		blocking.setName("BLOCK");
		blocking.setContext(live.getContext());
		blocking.start();
		live.addAppender(blocking);
		live.start();
		assertThat(live.isStarted()).isTrue();
		try {
			live.doAppend(event("e1"));
			long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
			while (live.getNumberOfElementsInQueue() > 0) {
				if (System.nanoTime() > deadline) {
					throw new AssertionError("worker did not take e1");
				}
				Thread.sleep(5);
			}
			Thread.sleep(30);
			live.doAppend(event("e2"));
			assertThat(live.getRemainingCapacity()).isEqualTo(0);
			long t0 = System.nanoTime();
			live.doAppend(event("e3"));
			long ms = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - t0);
			assertThat(AccessLogDropStats.dropped()).isGreaterThanOrEqualTo(1);
			assertThat(ms).isBetween(30L, 500L);
		} finally {
			release.countDown();
			live.stop();
		}
	}

	/** 必须用 SLF4J 绑定的 Logger，否则 prepareForDeferredProcessing 会因 mdcAdapter=null NPE。 */
	private static ILoggingEvent event(String msg) {
		Logger logger = (Logger) LoggerFactory.getLogger("ACCESS");
		LoggingEvent e = new LoggingEvent(AccessLogFilter.class.getName(), logger, Level.INFO, msg, null, null);
		e.setTimeStamp(System.currentTimeMillis());
		return e;
	}
}
