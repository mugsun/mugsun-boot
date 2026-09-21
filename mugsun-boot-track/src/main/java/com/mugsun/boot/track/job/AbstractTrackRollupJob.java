package com.mugsun.boot.track.job;

import cn.hutool.core.util.IdUtil;
import com.mugsun.boot.common.constant.TenantConstants;
import com.mugsun.boot.track.TrackDS;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;

/**
 * rollup 任务基座：游标补扫骨架 + 应用枚举 + 采样外推口径。
 * <p>与 {@code TrackEventStore} 同款实现纪律：JdbcTemplate 原生 SQL（不过 MyBatis-Flex 租户插件，
 * 平台级任务需跨租户全量聚合）；类级 {@link TrackDS} 路由埋点独立库。
 * <p><b>游标补扫（§4.4 钉死）</b>：读 {@code track_rollup_cursor}，从 {@code last_bucket} 后一窗口逐个补扫
 * 到最后闭合窗口（含），每窗口<b>全量重算 + SET 覆盖 upsert</b>（幂等可重入，严禁累加）后推进游标；
 * 追平后额外重算一次当前开放窗口（在途数据看板新鲜度），开放窗口不推进游标。
 * 单轮补扫窗口数封顶（常量），宕机深补扫逐轮追平，不一次爆量。
 * <p><b>时间规约</b>：窗口起点一律 UTC 墙钟 {@link LocalDateTime}（TIMESTAMP 列）；
 * received_at 比较一律 {@link java.time.OffsetDateTime}（UTC，TIMESTAMPTZ 列）——与 TrackEventStore 绑定规约一致。
 */
abstract class AbstractTrackRollupJob {

	protected final Logger log = LoggerFactory.getLogger(getClass());

	/** 应用范围：rollup 平台级全量处理（不经租户插件）；sampleRate 供量级类指标外推 */
	protected record AppScope(String appKey, String tenantId, int sampleRate) {
	}

	protected final JdbcTemplate jdbc;

	protected AbstractTrackRollupJob(DataSource dataSource) {
		this.jdbc = new JdbcTemplate(dataSource);
	}

	/** 游标任务键（stats_5m / stats_day / stats_vitals） */
	protected abstract String jobKey();

	/** 聚合一个窗口并覆盖写入（实现类保证幂等可重入） */
	protected abstract void aggregateWindow(AppScope app, LocalDateTime bucketStart);

	/** 全部未删应用（含停用：停用期无新事件游标原地不动，恢复启用后补扫不断档） */
	protected List<AppScope> listApps() {
		return jdbc.query(
			"SELECT app_key, tenant_id, sample_rate FROM track_app WHERE is_deleted = 0 ORDER BY app_key",
			(rs, i) -> new AppScope(rs.getString(1), rs.getString(2), rs.getInt(3)));
	}

	/**
	 * 单应用补扫主流程：从游标后一窗口补扫到最后闭合窗口（单轮封顶 maxWindows），
	 * 随后每轮无条件重算一次开放窗口（含在途数据；深补扫未追平也照常重算）。
	 *
	 * @param windowMinutes 窗口粒度（5=五分钟表；1440=日表）
	 * @param recomputeOpen 是否重算当前开放窗口（day 任务不需要——今日由看板当日直算）
	 * @return 本轮处理的闭合窗口数（不含开放窗口）
	 */
	protected int rollupApp(AppScope app, long windowMinutes, int maxWindows, boolean recomputeOpen) {
		LocalDateTime currentBucket = floorBucket(System.currentTimeMillis(), windowMinutes);
		LocalDateTime lastClosed = currentBucket.minus(Duration.ofMinutes(windowMinutes));
		LocalDateTime cursor = readCursor(app.appKey());
		LocalDateTime from;
		if (cursor == null) {
			Long firstMs = firstEventMs(app.appKey());
			if (firstMs == null) {
				// 无事件则不立游标（下轮重查 min）：严禁提前立到 lastClosed——
				// 消费重试/补发的迟到事件 received_at 可能早于该值，提前立标会永久漏聚
				return 0;
			}
			from = floorBucket(firstMs, windowMinutes);
		} else {
			from = cursor.plus(Duration.ofMinutes(windowMinutes));
		}
		int processed = 0;
		LocalDateTime w = from;
		while (!w.isAfter(lastClosed) && processed < maxWindows) {
			aggregateWindow(app, w);
			advanceCursor(app.appKey(), w);
			w = w.plus(Duration.ofMinutes(windowMinutes));
			processed++;
		}
		if (recomputeOpen) {
			// 开放窗口每轮无条件重算覆盖（received_at 为服务端接收时间，闭合窗口数据不可变，只有开放窗口在增长；
			// 深补扫未追平时也照常重算——看板近期数据可见性不等补扫完成）
			aggregateWindow(app, currentBucket);
		}
		return processed;
	}

	/** 读游标（无行 = 首次运行，返回 null） */
	protected LocalDateTime readCursor(String appKey) {
		List<LocalDateTime> rows = jdbc.query(
			"SELECT last_bucket FROM track_rollup_cursor WHERE job_key = ? AND app_key = ?",
			(rs, i) -> rs.getTimestamp(1).toLocalDateTime(), jobKey(), appKey);
		return rows.isEmpty() ? null : rows.get(0);
	}

	/** 推进游标（窗口聚合成功后调用；upsert 幂等） */
	protected void advanceCursor(String appKey, LocalDateTime lastBucket) {
		jdbc.update("INSERT INTO track_rollup_cursor (id, job_key, app_key, last_bucket, create_time, update_time)"
				+ " VALUES (?, ?, ?, ?, now(), now())"
				+ " ON CONFLICT (job_key, app_key) DO UPDATE SET last_bucket = EXCLUDED.last_bucket, update_time = now()",
			IdUtil.getSnowflakeNextId(), jobKey(), appKey, lastBucket);
	}

	/** 应用最早事件的接收时刻（epoch ms；无事件返回 null）——首次运行的补扫起点 */
	protected Long firstEventMs(String appKey) {
		return jdbc.queryForObject(
			"SELECT CAST(EXTRACT(EPOCH FROM min(received_at)) * 1000 AS BIGINT) FROM track_event WHERE app_key = ?",
			Long.class, appKey);
	}

	/** epoch 毫秒 → 窗口起点（UTC 墙钟；windowMinutes=1440 即 UTC 日界） */
	protected static LocalDateTime floorBucket(long epochMs, long windowMinutes) {
		long windowMs = windowMinutes * 60000L;
		long floored = (epochMs / windowMs) * windowMs;
		return LocalDateTime.ofInstant(Instant.ofEpochMilli(floored), ZoneOffset.UTC);
	}

	/**
	 * 采样外推（§4.4 钉死）：sample_rate&lt;100 时<b>量级类</b>指标（pv/event_count）除以采样率还原真实量级；
	 * 去重类（uv/session_count/bounce_count）与时长类一律<b>不外推</b>（调用方纪律，勿对去重类调用本方法）。
	 */
	protected static long extrapolate(long raw, int sampleRate) {
		if (sampleRate >= 100 || sampleRate < 1) {
			// 非法采样率不放大（防除零/爆量），按 100% 口径落原始值
			return raw;
		}
		return Math.round(raw * 100.0 / sampleRate);
	}

	/** 行租户口径与摄入侧一致：track_app.tenant_id 空则回退平台默认租户（事件映射同款规则） */
	protected static String rowTenant(String appTenantId) {
		return appTenantId == null || appTenantId.isBlank() ? TenantConstants.DEFAULT_TENANT_ID : appTenantId;
	}
}
