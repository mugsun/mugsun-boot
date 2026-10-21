package com.mugsun.boot.system.file;

import org.dromara.x.file.storage.core.FileInfo;
import org.dromara.x.file.storage.core.FileStorageService;
import org.dromara.x.file.storage.core.platform.FileStorage;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.ListOperations;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.util.ArrayDeque;
import java.util.Deque;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class PendingObjectCleanerTest {

	private final Deque<String> queue = new ArrayDeque<>();
	private final StringRedisTemplate redis = mock(StringRedisTemplate.class);
	private final FileStorageService fss = mock(FileStorageService.class);
	private final FileStorage storage = mock(FileStorage.class);
	private final com.mugsun.boot.system.mapper.SysAttachMapper attachMapper =
		mock(com.mugsun.boot.system.mapper.SysAttachMapper.class);

	@SuppressWarnings("unchecked")
	PendingObjectCleanerTest() {
		ListOperations<String, String> ops = mock(ListOperations.class);
		when(redis.opsForList()).thenReturn(ops);
		when(ops.rightPush(eq(PendingObjectCleaner.QUEUE_KEY), anyString()))
			.thenAnswer(i -> { queue.addLast(i.getArgument(1)); return (long) queue.size(); });
		when(ops.leftPop(PendingObjectCleaner.QUEUE_KEY)).thenAnswer(i -> queue.pollFirst());
		when(ops.size(PendingObjectCleaner.QUEUE_KEY)).thenAnswer(i -> (long) queue.size());
		when(fss.getFileStorageVerify(any(FileInfo.class))).thenReturn(storage);
	}

	private static FileInfo obj() {
		return new FileInfo().setPlatform("minio-1").setPath("tmp/").setFilename("0123456789abcdef0123456789abcdef.txt");
	}

	@Test
	void failedDeleteIsQueuedAndRetriedUntilItSucceeds() {
		when(storage.delete(any())).thenThrow(new RuntimeException("down"));
		PendingObjectCleaner c = new PendingObjectCleaner(redis, fss, attachMapper);
		c.deleteOrEnqueue(obj());
		assertThat(queue).hasSize(1);
		assertThat(c.drain()).isZero();
		assertThat(queue).hasSize(1).first().asString().contains("\"attempts\":1");

		org.mockito.Mockito.reset(storage);
		when(storage.delete(any())).thenReturn(true);
		assertThat(c.drain()).isEqualTo(1);
		assertThat(queue).isEmpty();
	}

	@Test
	void givesUpAfterMaxAttempts() {
		when(storage.delete(any())).thenThrow(new RuntimeException("down"));
		PendingObjectCleaner c = new PendingObjectCleaner(redis, fss, attachMapper);
		c.enqueue(obj(), PendingObjectCleaner.MAX_ATTEMPTS - 1);
		c.drain();
		assertThat(queue).isEmpty();
	}

	@Test
	void delayedItemWaitsUntilNotBefore() {
		when(storage.delete(any())).thenReturn(true);
		PendingObjectCleaner c = new PendingObjectCleaner(redis, fss, attachMapper);
		c.enqueueAfter(obj(), System.currentTimeMillis() + 60_000);
		assertThat(c.drain()).isZero();
		assertThat(queue).hasSize(1).first().asString().contains("\"attempts\":0");
		queue.clear();
		c.enqueueAfter(obj(), System.currentTimeMillis() - 1);
		assertThat(c.drain()).isEqualTo(1);
		assertThat(queue).isEmpty();
	}

	@Test
	void unlockScriptComparesTokenBeforeDelete() {
		assertThat(PendingObjectCleaner.UNLOCK.getScriptAsString())
			.contains("redis.call('get', KEYS[1]) == ARGV[1]").contains("redis.call('del', KEYS[1])");
	}

	@Test
	void registeredKeyIsNeverDeletedAndItemDropped() {
		when(attachMapper.selectCountByQuery(any(com.mybatisflex.core.query.QueryWrapper.class))).thenReturn(1L);
		when(storage.delete(any())).thenReturn(true);
		PendingObjectCleaner c = new PendingObjectCleaner(redis, fss, attachMapper);
		FileInfo fresh = new FileInfo().setPlatform("minio-1").setPath("private/")
			.setFilename("0123456789abcdef0123456789abcdef.png");
		c.deleteOrEnqueue(fresh, true);
		c.deleteOrEnqueue(obj());
		c.enqueue(obj(), 0);
		assertThat(c.drain()).isZero();
		org.mockito.Mockito.verify(storage, org.mockito.Mockito.never()).delete(any());
		assertThat(queue).as("已登记的队列项直接丢弃").isEmpty();
	}

	@Test
	void keyOutsideStagingAndNotFreshIsNeverDeleted() {
		when(storage.delete(any())).thenReturn(true);
		PendingObjectCleaner c = new PendingObjectCleaner(redis, fss, attachMapper);
		FileInfo published = new FileInfo().setPlatform("minio-1").setPath("public/")
			.setFilename("0123456789abcdef0123456789abcdef.png");
		FileInfo freshButOddName = new FileInfo().setPlatform("minio-1").setPath("private/").setFilename("report.pdf");
		FileInfo freshButOtherDir = new FileInfo().setPlatform("minio-1").setPath("avatar/")
			.setFilename("0123456789abcdef0123456789abcdef.png");
		c.deleteOrEnqueue(published);
		c.deleteOrEnqueue(freshButOddName, true);
		c.deleteOrEnqueue(freshButOtherDir, true);
		c.enqueue(published, 0);
		assertThat(c.drain()).isZero();
		org.mockito.Mockito.verify(storage, org.mockito.Mockito.never()).delete(any());
		assertThat(queue).isEmpty();
		// 本次新生成、未登记的随机 key 照常删除
		c.deleteOrEnqueue(published, true);
		org.mockito.Mockito.verify(storage).delete(any());
	}

	@Test
	void registryLookupFailureKeepsItemForRetry() {
		when(attachMapper.selectCountByQuery(any(com.mybatisflex.core.query.QueryWrapper.class)))
			.thenThrow(new RuntimeException("db down"));
		PendingObjectCleaner c = new PendingObjectCleaner(redis, fss, attachMapper);
		c.deleteOrEnqueue(obj());
		org.mockito.Mockito.verify(storage, org.mockito.Mockito.never()).delete(any());
		assertThat(queue).hasSize(1);
	}

	@Test
	void drainRequeuesRegistryFailureWithoutCountingAttempt() {
		when(attachMapper.selectCountByQuery(any(com.mybatisflex.core.query.QueryWrapper.class)))
			.thenThrow(new RuntimeException("db down"));
		PendingObjectCleaner c = new PendingObjectCleaner(redis, fss, attachMapper);
		c.enqueue(obj(), 3);
		assertThat(c.drain()).isZero();
		assertThat(queue).hasSize(1).first().asString().contains("\"attempts\":3");
		c.enqueue(obj(), PendingObjectCleaner.MAX_ATTEMPTS - 1);
		queue.pollFirst();
		c.drain();
		assertThat(queue).as("查表失败不算失败次数，满次数的项也不会被放弃").hasSize(1);
	}

	@Test
	void registryFailureRoundsAreCountedAndAlertedOnceButNeverDeleted() {
		when(attachMapper.selectCountByQuery(any(com.mybatisflex.core.query.QueryWrapper.class)))
			.thenThrow(new RuntimeException("db down"));
		ch.qos.logback.classic.Logger lg = (ch.qos.logback.classic.Logger)
			org.slf4j.LoggerFactory.getLogger(PendingObjectCleaner.class);
		ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent> app =
			new ch.qos.logback.core.read.ListAppender<>();
		app.start();
		lg.addAppender(app);
		try {
			PendingObjectCleaner c = new PendingObjectCleaner(redis, fss, attachMapper);
			c.enqueue(obj(), 2);
			c.drain();
			assertThat(queue).hasSize(1).first().asString().contains("\"unknownRounds\":1");
			queue.clear();
			c.enqueue(obj(), 2, 0L, false, PendingObjectCleaner.MAX_UNKNOWN_ROUNDS - 1);
			c.drain();
			c.drain();
			c.drain();
			assertThat(queue).as("超限也不删、不丢").hasSize(1).first().asString()
				.contains("\"unknownRounds\":" + (PendingObjectCleaner.MAX_UNKNOWN_ROUNDS + 2))
				.contains("\"attempts\":2");
			assertThat(app.list.stream().filter(e -> e.getLevel() == ch.qos.logback.classic.Level.ERROR))
				.as("只告警一次").hasSize(1);
			org.mockito.Mockito.verify(storage, org.mockito.Mockito.never()).delete(any());
		} finally {
			lg.detachAppender(app);
		}
	}

	@Test
	void registryFailureAlertIsSummarizedOncePerRound() {
		when(attachMapper.selectCountByQuery(any(com.mybatisflex.core.query.QueryWrapper.class)))
			.thenThrow(new RuntimeException("db down"));
		ch.qos.logback.classic.Logger lg = (ch.qos.logback.classic.Logger)
			org.slf4j.LoggerFactory.getLogger(PendingObjectCleaner.class);
		ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent> app =
			new ch.qos.logback.core.read.ListAppender<>();
		app.start();
		lg.addAppender(app);
		try {
			PendingObjectCleaner c = new PendingObjectCleaner(redis, fss, attachMapper);
			for (int i = 0; i < 8; i++) {
				c.enqueue(new FileInfo().setPlatform("minio-1").setPath("tmp/").setFilename("f" + i + ".png"),
					0, 0L, false, PendingObjectCleaner.MAX_UNKNOWN_ROUNDS);
			}
			c.drain();
			java.util.List<ch.qos.logback.classic.spi.ILoggingEvent> errors = app.list.stream()
				.filter(e -> e.getLevel() == ch.qos.logback.classic.Level.ERROR).toList();
			assertThat(errors).as("8 个条目同一轮越线，只汇总一条").hasSize(1);
			assertThat(errors.get(0).getFormattedMessage()).startsWith("8 个待删对象")
				.contains("tmp/f0.png").contains("tmp/f4.png").doesNotContain("tmp/f5.png");
			assertThat(queue).as("仍不删不丢").hasSize(8);
		} finally {
			lg.detachAppender(app);
		}
	}

	@Test
	void delayedFreshItemIsCheckedAgainstRegistryBeforeDelete() {
		when(storage.delete(any())).thenReturn(true);
		PendingObjectCleaner c = new PendingObjectCleaner(redis, fss, attachMapper);
		FileInfo fresh = new FileInfo().setPlatform("minio-1").setPath("private/")
			.setFilename("0123456789abcdef0123456789abcdef.png");
		c.enqueueFreshAfter(fresh, System.currentTimeMillis() + 60_000);
		assertThat(queue).hasSize(1).first().asString().contains("\"fresh\":true").contains("notBefore");
		assertThat(c.drain()).as("没到时间不删").isZero();
		// 到期时已登记（登记其实成功了）：不删并丢弃
		queue.clear();
		when(attachMapper.selectCountByQuery(any(com.mybatisflex.core.query.QueryWrapper.class))).thenReturn(1L);
		c.enqueueFreshAfter(fresh, System.currentTimeMillis() - 1);
		assertThat(c.drain()).isZero();
		org.mockito.Mockito.verify(storage, org.mockito.Mockito.never()).delete(any());
		assertThat(queue).isEmpty();
		// 到期时未登记：删
		when(attachMapper.selectCountByQuery(any(com.mybatisflex.core.query.QueryWrapper.class))).thenReturn(0L);
		c.enqueueFreshAfter(fresh, System.currentTimeMillis() - 1);
		assertThat(c.drain()).isEqualTo(1);
	}

	@Test
	void storedNameAlwaysMatchesRandomNamePattern() {
		for (String ext : UploadPolicy.ALLOWED_EXT) {
			for (int i = 0; i < 20; i++) {
				assertThat(PendingObjectCleaner.RANDOM_NAME.matcher(UploadPolicy.storedName(ext)).matches()).as(ext).isTrue();
			}
		}
	}
}