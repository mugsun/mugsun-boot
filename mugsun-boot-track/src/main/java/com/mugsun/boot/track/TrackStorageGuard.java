package com.mugsun.boot.track;

import com.mugsun.boot.common.constant.TrackConstants;
import com.mugsun.boot.system.service.ParamService;
import org.dromara.x.file.storage.core.FileStorageService;
import org.dromara.x.file.storage.spring.SpringFileStorageProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;
import java.util.function.Supplier;
import java.util.function.ToLongFunction;

/**
 * 匿名写对象存储（回放块、接口响应体）的容量闸，写入前检查，两道任一不过就拒收：
 * <ul>
 * <li>磁盘余量：默认存储平台落本机磁盘（local-plus）时，存储目录所在分区的可用空间低于
 *     {@value TrackConstants#PARAM_STORAGE_MIN_FREE} 就拒收（503，空间腾出来后 SDK 重试即可）。写满系统盘会拖垮同机的
 *     Docker、日志和所有服务，这道保的是整台机器。余量读数缓存 {@value TrackConstants#STORAGE_FREE_CHECK_INTERVAL_MS} 毫秒。</li>
 * <li>每日配额：单应用和全平台各记一个当日（UTC）落盘字节数，加上这次会超过上限就拒收（429，SDK 不再重试）。
 *     拒收的字节不计入；预占之后没写成的，调用方用 {@link #release} 退回。单应用上限让一个公开的应用标识被刷时
 *     不挤占其它应用，全平台上限封住用多个应用标识叠加的写入。</li>
 * </ul>
 * 配额计数在 Redis，Redis 故障时放行并告警（同摄入链路其它计数），磁盘余量这道照常生效。
 */
@Component
public class TrackStorageGuard {

	private static final Logger log = LoggerFactory.getLogger(TrackStorageGuard.class);

	private static final DateTimeFormatter DAY_FORMAT =
		DateTimeFormatter.ofPattern(TrackConstants.STORAGE_QUOTA_DAY_PATTERN).withZone(ZoneOffset.UTC);

	/** 受管的匿名写入通道：指标 reason 前缀 + 拒收时的三句话（每种情况写成整句，翻译时不用拼词） */
	public enum Channel {
		REPLAY("replay",
			"服务器存储空间不足，暂时不接收回放。请稍后再试",
			"这个应用今天的回放和响应体存储额度已经用完，这段回放没有收下。请明天再试",
			"平台今天的回放和响应体存储额度已经用完，这段回放没有收下。请明天再试"),
		API_BODY("api_body",
			"服务器存储空间不足，暂时不接收响应体。请稍后再试",
			"这个应用今天的回放和响应体存储额度已经用完，这段响应体没有收下。请明天再试",
			"平台今天的回放和响应体存储额度已经用完，这段响应体没有收下。请明天再试");

		private final String metricPrefix;
		private final String diskLow;
		private final String appQuotaUsedUp;
		private final String platformQuotaUsedUp;

		Channel(String metricPrefix, String diskLow, String appQuotaUsedUp, String platformQuotaUsedUp) {
			this.metricPrefix = metricPrefix;
			this.diskLow = diskLow;
			this.appQuotaUsedUp = appQuotaUsedUp;
			this.platformQuotaUsedUp = platformQuotaUsedUp;
		}
	}

	/** 一次预占：写入失败时交给 {@link #release} 退回（退回预占时那一天的计数，跨零点也不会退错） */
	public record Reservation(String appCounter, String platformCounter, long bytes) {
		/** Redis 不可用时放行的占位：没有计数可退 */
		static final Reservation NONE = new Reservation(null, null, 0);
	}

	private final StringRedisTemplate redis;
	private final ParamService paramService;
	private final TrackIngestMetrics metrics;
	private final Supplier<Path> storageDir;
	private final ToLongFunction<Path> freeSpace;
	private final LongSupplier clock;

	private volatile long freeCheckedAt = Long.MIN_VALUE;
	private volatile long lastFree = -1;
	private volatile boolean diskLow;
	private volatile boolean freeUnreadableLogged;
	/** 配额用完的告警每个范围每天只打一次（键：平台或应用标识 → 上次告警的日期） */
	private final Map<String, String> quotaWarnedDay = new ConcurrentHashMap<>();

	@Autowired
	public TrackStorageGuard(StringRedisTemplate redis, ParamService paramService, TrackIngestMetrics metrics,
							 FileStorageService fileStorageService, SpringFileStorageProperties properties) {
		this(redis, paramService, metrics, () -> localStorageDir(fileStorageService.getDefaultPlatform(), properties),
			TrackStorageGuard::usableSpace, System::currentTimeMillis);
	}

	/** 测试用：可注入存储目录、取余量的函数（返回 -1 表示读不到）和时钟 */
	TrackStorageGuard(StringRedisTemplate redis, ParamService paramService, TrackIngestMetrics metrics,
					  Supplier<Path> storageDir, ToLongFunction<Path> freeSpace, LongSupplier clock) {
		this.redis = redis;
		this.paramService = paramService;
		this.metrics = metrics;
		this.storageDir = storageDir;
		this.freeSpace = freeSpace;
		this.clock = clock;
	}

	/**
	 * 磁盘余量检查：低于下限抛 503。余量读不到或默认平台不在本机磁盘时放行。
	 * 下限每次现读参数，调整后立即生效；只缓存余量读数。
	 */
	public void assertDiskRoom(Channel channel) {
		long free = freeBytes();
		if (free < 0) {
			return;
		}
		long minFree = paramLong(TrackConstants.PARAM_STORAGE_MIN_FREE, TrackConstants.DEFAULT_STORAGE_MIN_FREE_BYTES);
		boolean low = free < minFree;
		if (low != diskLow) {
			diskLow = low;
			if (low) {
				log.warn("埋点存储盘剩余 {} MB，低于下限 {} MB（参数 {}），暂停接收回放和响应体",
					free >> 20, minFree >> 20, TrackConstants.PARAM_STORAGE_MIN_FREE);
			} else {
				log.info("埋点存储盘剩余 {} MB，已回到下限 {} MB 以上，恢复接收回放和响应体", free >> 20, minFree >> 20);
			}
		}
		if (low) {
			metrics.dropped(channel.metricPrefix + "_disk_low", 1);
			throw new TrackCollectException(503, channel.diskLow, TrackRejectReason.STORAGE_FULL);
		}
	}

	/**
	 * 预占当日配额（先单应用后全平台，任一超出就把已加的退掉并抛 429）。
	 *
	 * @param bytes 将要落盘的字节数（gzip 后）
	 * @return 预占凭据；写入失败时交给 {@link #release}
	 */
	public Reservation reserve(Channel channel, String appKey, long bytes) {
		String day = DAY_FORMAT.format(Instant.ofEpochMilli(clock.getAsLong()));
		String platformCounter = TrackConstants.STORAGE_QUOTA_KEY_PREFIX + day;
		String appCounter = platformCounter + ":" + appKey;
		long appMax = paramLong(TrackConstants.PARAM_STORAGE_APP_DAILY_MAX, TrackConstants.DEFAULT_STORAGE_APP_DAILY_MAX_BYTES);
		long platformMax = paramLong(TrackConstants.PARAM_STORAGE_DAILY_MAX, TrackConstants.DEFAULT_STORAGE_DAILY_MAX_BYTES);
		try {
			if (!take(appCounter, bytes, appMax)) {
				throw overQuota(channel, day, appKey, false, appMax);
			}
			if (!take(platformCounter, bytes, platformMax)) {
				redis.opsForValue().decrement(appCounter, bytes);
				throw overQuota(channel, day, "*", true, platformMax);
			}
			return new Reservation(appCounter, platformCounter, bytes);
		} catch (TrackCollectException e) {
			throw e;
		} catch (RuntimeException e) {
			log.warn("埋点存储配额计数 Redis 不可用，本次放行：{}", e.getMessage());
			return Reservation.NONE;
		}
	}

	/** 退回预占（入队或落储失败时）；Redis 故障只告警，当日额度少算这一笔 */
	public void release(Reservation reservation) {
		if (reservation == null || reservation.appCounter() == null) {
			return;
		}
		try {
			redis.opsForValue().decrement(reservation.appCounter(), reservation.bytes());
			redis.opsForValue().decrement(reservation.platformCounter(), reservation.bytes());
		} catch (RuntimeException e) {
			log.warn("埋点存储配额退回失败（当日额度少算 {} 字节）：{}", reservation.bytes(), e.getMessage());
		}
	}

	/** INCRBY 后超过上限就退回并返回 false；首次建键时设 TTL */
	private boolean take(String counter, long bytes, long max) {
		Long total = redis.opsForValue().increment(counter, bytes);
		if (total != null && total == bytes) {
			redis.expire(counter, Duration.ofSeconds(TrackConstants.STORAGE_QUOTA_KEY_TTL_SECONDS));
		}
		if (total != null && total > max) {
			redis.opsForValue().decrement(counter, bytes);
			return false;
		}
		return true;
	}

	private TrackCollectException overQuota(Channel channel, String day, String scopeKey, boolean platform, long max) {
		metrics.dropped(channel.metricPrefix + "_quota_exceeded", 1);
		if (!day.equals(quotaWarnedDay.put(scopeKey, day))) {
			log.warn("{}（{}）今天的回放和响应体落盘已到上限 {} MB，今天剩下的一律拒收；如果不是异常流量，调高参数 {} 或 {}",
				platform ? "平台" : "这个应用", scopeKey, max >> 20, TrackConstants.PARAM_STORAGE_APP_DAILY_MAX,
				TrackConstants.PARAM_STORAGE_DAILY_MAX);
		}
		return new TrackCollectException(429, platform ? channel.platformQuotaUsedUp : channel.appQuotaUsedUp,
			TrackRejectReason.STORAGE_FULL);
	}

	/** 可用空间读数（缓存 {@value TrackConstants#STORAGE_FREE_CHECK_INTERVAL_MS} 毫秒）；不在本机磁盘或读不到返回 -1 */
	private long freeBytes() {
		long now = clock.getAsLong();
		if (freeCheckedAt != Long.MIN_VALUE && now - freeCheckedAt < TrackConstants.STORAGE_FREE_CHECK_INTERVAL_MS) {
			return lastFree;
		}
		Path dir = storageDir.get();
		long free = dir == null ? -1 : freeSpace.applyAsLong(dir);
		if (dir != null && free < 0 && !freeUnreadableLogged) {
			log.warn("读不到埋点存储目录 {} 所在磁盘的剩余空间，磁盘余量检查暂时失效", dir);
		}
		freeUnreadableLogged = dir != null && free < 0;
		lastFree = free;
		freeCheckedAt = now;
		return free;
	}

	/** 默认平台是 yaml 里的 local-plus 时返回它的存储根目录，否则 null（对象存储的容量不归本机管） */
	static Path localStorageDir(String defaultPlatform, SpringFileStorageProperties properties) {
		if (defaultPlatform == null || properties.getLocalPlus() == null) {
			return null;
		}
		return properties.getLocalPlus().stream()
			.filter(config -> defaultPlatform.equals(config.getPlatform()))
			.findFirst()
			.map(config -> Path.of(config.getStoragePath() == null || config.getStoragePath().isBlank() ? "/" : config.getStoragePath()))
			.orElse(null);
	}

	/** 目录还没建时向上找最近的已存在目录；读不到返回 -1 */
	static long usableSpace(Path dir) {
		try {
			Path p = dir.toAbsolutePath();
			while (p != null && !Files.exists(p)) {
				p = p.getParent();
			}
			return p == null ? -1 : Files.getFileStore(p).getUsableSpace();
		} catch (IOException | RuntimeException e) {
			return -1;
		}
	}

	/** sys_param 取长整（缺失/非法/小于 1 回退兜底默认） */
	private long paramLong(String key, long defaultValue) {
		String value = paramService.getValue(key);
		if (value == null) {
			return defaultValue;
		}
		try {
			long parsed = Long.parseLong(value.trim());
			return parsed < 1 ? defaultValue : parsed;
		} catch (NumberFormatException e) {
			return defaultValue;
		}
	}
}
