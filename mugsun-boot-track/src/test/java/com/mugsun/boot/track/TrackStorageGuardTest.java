package com.mugsun.boot.track;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.mugsun.boot.common.constant.TrackConstants;
import com.mugsun.boot.db.MigrationBaseline;
import com.mugsun.boot.system.service.ParamService;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.dromara.x.file.storage.spring.SpringFileStorageProperties;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

/** 存储容量闸：磁盘余量低于下限 503、单应用/全平台当日配额超出 429 且拒收的字节不计入、退回、Redis 故障放行 */
class TrackStorageGuardTest {

	private static final long GB = 1L << 30;
	private static final long DAY_MS = 86_400_000L;
	private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("yyyyMMdd").withZone(ZoneOffset.UTC);

	private final Map<String, Long> counters = new HashMap<>();
	private final Map<String, String> params = new HashMap<>();
	private final StringRedisTemplate redis = mock(StringRedisTemplate.class);
	@SuppressWarnings("unchecked")
	private final ValueOperations<String, String> ops = mock(ValueOperations.class);
	private final ParamService paramService = mock(ParamService.class);
	private final TrackIngestMetrics metrics = mock(TrackIngestMetrics.class);
	private final AtomicLong free = new AtomicLong(50 * GB);
	private final AtomicInteger freeReads = new AtomicInteger();
	private final AtomicLong now = new AtomicLong(1_795_000_000_000L);
	private Path dir = Path.of("/data/files");
	private final TrackStorageGuard guard;

	TrackStorageGuardTest() {
		when(redis.opsForValue()).thenReturn(ops);
		when(ops.increment(anyString(), anyLong())).thenAnswer(inv ->
			counters.merge(inv.getArgument(0), inv.<Long>getArgument(1), Long::sum));
		when(ops.decrement(anyString(), anyLong())).thenAnswer(inv ->
			counters.merge(inv.getArgument(0), -inv.<Long>getArgument(1), Long::sum));
		when(paramService.getValue(anyString())).thenAnswer(inv -> params.get(inv.<String>getArgument(0)));
		guard = new TrackStorageGuard(redis, paramService, metrics, () -> dir,
			p -> {
				freeReads.incrementAndGet();
				return free.get();
			},
			now::get);
	}

	@Test
	void diskBelowMinFreeRejects503AndReadingIsCached() {
		free.set(4 * GB);
		TrackCollectException e = catchThrowableOfType(TrackCollectException.class,
			() -> guard.assertDiskRoom(TrackStorageGuard.Channel.REPLAY));
		assertThat(e.getStatus()).as("默认下限 5GB，剩 4GB 应拒收").isEqualTo(503);
		assertThat(e.getReason()).isEqualTo(TrackRejectReason.STORAGE_FULL);
		assertThat(e.getMessage()).contains("存储空间不足").contains("回放");
		verify(metrics).dropped("replay_disk_low", 1);

		free.set(40 * GB);
		now.addAndGet(TrackConstants.STORAGE_FREE_CHECK_INTERVAL_MS - 1);
		assertThatThrownBy(() -> guard.assertDiskRoom(TrackStorageGuard.Channel.API_BODY))
			.as("缓存期内复用上次读数").isInstanceOf(TrackCollectException.class);
		verify(metrics).dropped("api_body_disk_low", 1);
		assertThat(freeReads.get()).isEqualTo(1);

		now.addAndGet(1);
		guard.assertDiskRoom(TrackStorageGuard.Channel.API_BODY);
		assertThat(freeReads.get()).as("缓存到期重读，空间腾出来后恢复接收").isEqualTo(2);
	}

	@Test
	void minFreeParamTakesEffectWithoutWaitingForCache() {
		free.set(40 * GB);
		guard.assertDiskRoom(TrackStorageGuard.Channel.REPLAY);
		params.put(TrackConstants.PARAM_STORAGE_MIN_FREE, String.valueOf(41 * GB));
		assertThatThrownBy(() -> guard.assertDiskRoom(TrackStorageGuard.Channel.REPLAY))
			.isInstanceOf(TrackCollectException.class);
		assertThat(freeReads.get()).as("只缓存余量读数，下限每次现读").isEqualTo(1);
	}

	@Test
	void unreadableOrRemoteStorageIsNotBlocked() {
		free.set(-1);
		guard.assertDiskRoom(TrackStorageGuard.Channel.REPLAY);
		dir = null;
		now.addAndGet(TrackConstants.STORAGE_FREE_CHECK_INTERVAL_MS);
		guard.assertDiskRoom(TrackStorageGuard.Channel.REPLAY);
		assertThat(freeReads.get()).as("默认平台不在本机磁盘时不读余量").isEqualTo(1);
		verify(metrics, never()).dropped(anyString(), anyLong());
	}

	@Test
	void appQuotaRejects429WithoutCountingRejectedBytes() {
		params.put(TrackConstants.PARAM_STORAGE_APP_DAILY_MAX, "100");
		guard.reserve(TrackStorageGuard.Channel.REPLAY, "ak1", 60);
		TrackCollectException e = catchThrowableOfType(TrackCollectException.class,
			() -> guard.reserve(TrackStorageGuard.Channel.API_BODY, "ak1", 60));
		assertThat(e.getStatus()).isEqualTo(429);
		assertThat(e.getReason()).isEqualTo(TrackRejectReason.STORAGE_FULL);
		assertThat(e.getMessage()).startsWith("这个应用今天的").contains("响应体");
		verify(metrics).dropped("api_body_quota_exceeded", 1);
		assertThat(counters).containsEntry(appCounter("ak1"), 60L).containsEntry(platformCounter(), 60L);

		guard.reserve(TrackStorageGuard.Channel.REPLAY, "ak2", 60);
		assertThat(counters).as("另一个应用不受影响").containsEntry(appCounter("ak2"), 60L);

		now.addAndGet(DAY_MS);
		guard.reserve(TrackStorageGuard.Channel.REPLAY, "ak1", 60);
		assertThat(counters).as("第二天重新计").containsEntry(appCounter("ak1"), 60L);
	}

	@Test
	void platformQuotaRejects429AndRollsBackAppCounter() {
		params.put(TrackConstants.PARAM_STORAGE_DAILY_MAX, "100");
		guard.reserve(TrackStorageGuard.Channel.REPLAY, "ak1", 60);
		TrackCollectException e = catchThrowableOfType(TrackCollectException.class,
			() -> guard.reserve(TrackStorageGuard.Channel.REPLAY, "ak2", 60));
		assertThat(e.getStatus()).isEqualTo(429);
		assertThat(e.getMessage()).startsWith("平台今天的");
		verify(metrics).dropped("replay_quota_exceeded", 1);
		assertThat(counters).containsEntry(appCounter("ak2"), 0L).containsEntry(platformCounter(), 60L);
	}

	@Test
	void releaseReturnsReservedBytes() {
		TrackStorageGuard.Reservation reservation = guard.reserve(TrackStorageGuard.Channel.API_BODY, "ak1", 500);
		guard.release(reservation);
		assertThat(counters).containsEntry(appCounter("ak1"), 0L).containsEntry(platformCounter(), 0L);
	}

	@Test
	void redisFailureFailsOpen() {
		when(ops.increment(anyString(), anyLong())).thenThrow(new RedisConnectionFailureException("down"));
		TrackStorageGuard.Reservation reservation = guard.reserve(TrackStorageGuard.Channel.REPLAY, "ak1", 500);
		guard.release(reservation);
		verify(ops, never()).decrement(anyString(), anyLong());
		verify(metrics, never()).dropped(anyString(), anyLong());
	}

	@Test
	void localStorageDirFollowsDefaultPlatform() {
		SpringFileStorageProperties.SpringLocalPlusConfig local = new SpringFileStorageProperties.SpringLocalPlusConfig();
		local.setPlatform("local-plus-1");
		local.setStoragePath("/var/lib/mugsun/files/");
		SpringFileStorageProperties props = new SpringFileStorageProperties();
		props.setLocalPlus(List.of(local));
		assertThat(TrackStorageGuard.localStorageDir("local-plus-1", props)).isEqualTo(Path.of("/var/lib/mugsun/files/"));
		assertThat(TrackStorageGuard.localStorageDir("minio-1", props)).isNull();
		assertThat(TrackStorageGuard.localStorageDir(null, props)).isNull();
	}

	@Test
	void migrationsSeedTheSameDefaultsAsCode() {
		Map<String, Long> defaults = Map.of(
			TrackConstants.PARAM_STORAGE_DAILY_MAX, TrackConstants.DEFAULT_STORAGE_DAILY_MAX_BYTES,
			TrackConstants.PARAM_STORAGE_APP_DAILY_MAX, TrackConstants.DEFAULT_STORAGE_APP_DAILY_MAX_BYTES,
			TrackConstants.PARAM_STORAGE_MIN_FREE, TrackConstants.DEFAULT_STORAGE_MIN_FREE_BYTES);
		for (String dir : List.of("migration", "migration-dm", "migration-og")) {
			String sql = MigrationBaseline.section(dir, "V22__track_storage_quota_params.sql");
			defaults.forEach((key, value) ->
				assertThat(sql).as(dir + " " + key).contains("'" + key + "', '" + value + "'"));
		}
	}

	private String platformCounter() {
		return TrackConstants.STORAGE_QUOTA_KEY_PREFIX + DAY.format(Instant.ofEpochMilli(now.get()));
	}

	private String appCounter(String appKey) {
		return platformCounter() + ":" + appKey;
	}
}
