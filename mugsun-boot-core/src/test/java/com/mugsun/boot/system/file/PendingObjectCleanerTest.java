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
}