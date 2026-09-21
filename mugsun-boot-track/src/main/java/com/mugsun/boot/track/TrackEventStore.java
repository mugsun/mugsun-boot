package com.mugsun.boot.track;

import cn.hutool.core.util.IdUtil;
import com.mugsun.boot.common.constant.TrackConstants;
import org.springframework.jdbc.core.BatchPreparedStatementSetter;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Types;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;

/**
 * 埋点事件存储（PG 实现，{@code @TrackDS} 路由埋点独立库）：消费侧批量落库的唯一出口。
 * <p>刻意用 JdbcTemplate 原生批量 SQL 而非 MyBatis-Flex mapper：
 * ① 分区表批量 INSERT 需显式 {@code ON CONFLICT (event_id, received_at) DO NOTHING} 同接收窗兜底；
 * ② 消费批跨租户混合，原生 SQL 不经 MyBatis 拦截器，天然绕开租户行级插件对混合批写的条件改写
 * （配合调用方 {@code TenantContext.ignore} 双保险，每行 tenant_id 显式自带）；
 * ③ 会话 upsert 写死 LEAST/GREATEST/CASE WHEN/累加/置位语义（乱序安全，绝不用裸 EXCLUDED 覆盖）。
 * <p>时间绑定规约：TIMESTAMPTZ 列一律 {@link OffsetDateTime}（UTC）；TIMESTAMP 列一律 UTC 墙钟
 * {@link LocalDateTime}——与库内 now()（容器 UTC）口径一致，会话时长差值计算无时区漂移。
 */
@Component
@TrackDS
public class TrackEventStore {

	/**
	 * 会话增量聚合（消费侧批内同 session 先聚合的参数对象）：
	 * 聚合一口径——startTime/endTime 取批内校时后 ts 极值，entryPath/exitPath 取极值事件的 urlPath，
	 * pageviews/eventCount 为批内计数（落库累加），hasError/settled 为批内置位（落库 GREATEST 不回退）。
	 */
	public record SessionAggregate(String sessionId, String appKey, String tenantId, String distinctId, Long userId,
								   LocalDateTime startTime, LocalDateTime endTime, int durationMs,
								   int pageviews, int eventCount, String entryPath, String exitPath,
								   String referrerDomain, String utmSource, String browser, String os, String device,
								   String ipRegion, int hasError, int settled) {
	}

	/** $identify 待绑定（已过 token 一致性核对）：首绑语义——冲突只刷 last_seen_time，user_id 绝不覆盖 */
	public record IdentityBinding(String appKey, String distinctId, Long userId, String tenantId) {
	}

	/** 未见事件名自动注册（first/last_seen） */
	public record EventDefSeen(String appKey, String eventName, String tenantId) {
	}

	/** 事件批量 INSERT（ON CONFLICT 同接收窗兜底） */
	private static final String EVENT_INSERT = "INSERT INTO track_event (id, event_id, app_key, event_name,"
		+ " client_ts, ts, received_at, clock_skewed, distinct_id, user_id, session_id, tenant_id,"
		+ " url_path, route_path, page_title, referrer_domain, utm_source, utm_medium, utm_campaign,"
		+ " browser, os, device, ip, ip_region, duration_ms, error_fingerprint, geo_lon, geo_lat, props)"
		+ " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb)"
		+ " ON CONFLICT (event_id, received_at) DO NOTHING";

	/** 会话增量 upsert：乱序安全语义写死（LEAST/GREATEST/CASE WHEN/累加/置位不回退） */
	private static final String SESSION_UPSERT = "INSERT INTO track_session (id, session_id, app_key, tenant_id,"
		+ " distinct_id, user_id, start_time, end_time, duration_ms, pageviews, event_count, is_bounce,"
		+ " entry_path, exit_path, referrer_domain, utm_source, browser, os, device, ip_region,"
		+ " has_error, has_replay, settled, create_time, update_time, is_deleted)"
		+ " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 0, ?, ?, ?, ?, ?, ?, ?, ?, ?, 0, ?, now(), now(), 0)"
		+ " ON CONFLICT (session_id) WHERE is_deleted = 0 DO UPDATE SET"
		+ " start_time = LEAST(track_session.start_time, EXCLUDED.start_time),"
		+ " end_time = GREATEST(track_session.end_time, EXCLUDED.end_time),"
		+ " duration_ms = CAST(EXTRACT(EPOCH FROM (GREATEST(track_session.end_time, EXCLUDED.end_time)"
		+ " - LEAST(track_session.start_time, EXCLUDED.start_time))) * 1000 AS INT),"
		+ " entry_path = CASE WHEN EXCLUDED.entry_path IS NOT NULL AND EXCLUDED.start_time < track_session.start_time"
		+ " THEN EXCLUDED.entry_path ELSE track_session.entry_path END,"
		+ " exit_path = CASE WHEN EXCLUDED.exit_path IS NOT NULL AND EXCLUDED.end_time > track_session.end_time"
		+ " THEN EXCLUDED.exit_path ELSE track_session.exit_path END,"
		+ " pageviews = track_session.pageviews + EXCLUDED.pageviews,"
		+ " event_count = track_session.event_count + EXCLUDED.event_count,"
		+ " has_error = GREATEST(track_session.has_error, EXCLUDED.has_error),"
		+ " settled = GREATEST(track_session.settled, EXCLUDED.settled),"
		+ " user_id = COALESCE(track_session.user_id, EXCLUDED.user_id),"
		+ " update_time = now()";

	/** identity upsert：user_id 首绑写入后绝不覆盖（共享设备防串号），冲突只刷 last_seen_time */
	private static final String IDENTITY_UPSERT = "INSERT INTO track_identity (id, app_key, distinct_id, user_id,"
		+ " tenant_id, first_bind_time, last_seen_time, create_time, update_time, is_deleted)"
		+ " VALUES (?, ?, ?, ?, ?, now(), now(), now(), now(), 0)"
		+ " ON CONFLICT (app_key, distinct_id) WHERE is_deleted = 0 DO UPDATE SET"
		+ " last_seen_time = now(), update_time = now()";

	/** 事件定义自动注册：未见事件名 upsert（first_seen/last_seen），冲突只刷 last_seen_time */
	private static final String EVENT_DEF_UPSERT = "INSERT INTO track_event_def (id, app_key, event_name, status,"
		+ " first_seen_time, last_seen_time, tenant_id, create_time, update_time, is_deleted)"
		+ " VALUES (?, ?, ?, 1, now(), now(), ?, now(), now(), 0)"
		+ " ON CONFLICT (app_key, event_name) WHERE is_deleted = 0 DO UPDATE SET"
		+ " last_seen_time = now(), update_time = now()";

	private final JdbcTemplate jdbc;

	/** 注入全局路由数据源（FlexDataSource）：连接获取时按 {@code @TrackDS} 压栈的 DataSourceKey 路由埋点库 */
	public TrackEventStore(DataSource dataSource) {
		this.jdbc = new JdbcTemplate(dataSource);
	}

	/** 批量 INSERT 事件流水（ON CONFLICT (event_id, received_at) DO NOTHING 同接收窗幂等兜底） */
	public void insertEvents(List<TrackIngestEvent> events) {
		jdbc.batchUpdate(EVENT_INSERT, new BatchPreparedStatementSetter() {
			@Override
			public void setValues(PreparedStatement ps, int i) throws SQLException {
				TrackIngestEvent e = events.get(i);
				ps.setLong(1, IdUtil.getSnowflakeNextId());
				ps.setString(2, e.getEventId());
				ps.setString(3, e.getAppKey());
				ps.setString(4, e.getEventName());
				ps.setObject(5, offsetTs(e.getClientTsMs()));
				ps.setObject(6, offsetTs(e.getTsMs()));
				ps.setObject(7, offsetTs(e.getReceivedAtMs()));
				ps.setInt(8, e.getClockSkewed());
				ps.setString(9, e.getDistinctId());
				if (e.getUserId() == null) {
					ps.setNull(10, Types.BIGINT);
				} else {
					ps.setLong(10, e.getUserId());
				}
				ps.setString(11, e.getSessionId());
				ps.setString(12, e.getTenantId());
				ps.setString(13, e.getUrlPath());
				ps.setString(14, e.getRoutePath());
				ps.setString(15, e.getPageTitle());
				ps.setString(16, e.getReferrerDomain());
				ps.setString(17, e.getUtmSource());
				ps.setString(18, e.getUtmMedium());
				ps.setString(19, e.getUtmCampaign());
				ps.setString(20, e.getBrowser());
				ps.setString(21, e.getOs());
				ps.setString(22, e.getDevice());
				ps.setString(23, e.getIp());
				ps.setString(24, e.getIpRegion());
				if (e.getDurationMs() == null) {
					ps.setNull(25, Types.INTEGER);
				} else {
					ps.setInt(25, e.getDurationMs());
				}
				ps.setString(26, e.getErrorFingerprint());
				if (e.getGeoLon() == null) {
					ps.setNull(27, Types.NUMERIC);
				} else {
					ps.setDouble(27, e.getGeoLon());
				}
				if (e.getGeoLat() == null) {
					ps.setNull(28, Types.NUMERIC);
				} else {
					ps.setDouble(28, e.getGeoLat());
				}
				// jsonb 列：Types.OTHER 传 JSON 文本（PG 驱动按 jsonb 绑定）
				ps.setObject(29, e.getPropsJson() == null ? "{}" : e.getPropsJson(), Types.OTHER);
			}

			@Override
			public int getBatchSize() {
				return events.size();
			}
		});
	}

	/** 会话增量 upsert（批内已按 session 聚合；SQL 写死乱序安全语义） */
	public void upsertSessions(List<SessionAggregate> sessions) {
		jdbc.batchUpdate(SESSION_UPSERT, new BatchPreparedStatementSetter() {
			@Override
			public void setValues(PreparedStatement ps, int i) throws SQLException {
				SessionAggregate s = sessions.get(i);
				ps.setLong(1, IdUtil.getSnowflakeNextId());
				ps.setString(2, s.sessionId());
				ps.setString(3, s.appKey());
				ps.setString(4, s.tenantId());
				ps.setString(5, s.distinctId());
				if (s.userId() == null) {
					ps.setNull(6, Types.BIGINT);
				} else {
					ps.setLong(6, s.userId());
				}
				ps.setObject(7, s.startTime());
				ps.setObject(8, s.endTime());
				ps.setInt(9, s.durationMs());
				ps.setInt(10, s.pageviews());
				ps.setInt(11, s.eventCount());
				ps.setString(12, s.entryPath());
				ps.setString(13, s.exitPath());
				ps.setString(14, s.referrerDomain());
				ps.setString(15, s.utmSource());
				ps.setString(16, s.browser());
				ps.setString(17, s.os());
				ps.setString(18, s.device());
				ps.setString(19, s.ipRegion());
				ps.setInt(20, s.hasError());
				ps.setInt(21, s.settled());
			}

			@Override
			public int getBatchSize() {
				return sessions.size();
			}
		});
	}

	/** $identify 绑定 upsert（首绑不覆盖：冲突只刷 last_seen_time） */
	public void upsertIdentities(List<IdentityBinding> bindings) {
		jdbc.batchUpdate(IDENTITY_UPSERT, new BatchPreparedStatementSetter() {
			@Override
			public void setValues(PreparedStatement ps, int i) throws SQLException {
				IdentityBinding b = bindings.get(i);
				ps.setLong(1, IdUtil.getSnowflakeNextId());
				ps.setString(2, b.appKey());
				ps.setString(3, b.distinctId());
				ps.setLong(4, b.userId());
				ps.setString(5, b.tenantId());
			}

			@Override
			public int getBatchSize() {
				return bindings.size();
			}
		});
	}

	/** 未见事件名自动注册（冲突只刷 last_seen_time；status 停用语义由采集端后续波次接） */
	public void upsertEventDefs(List<EventDefSeen> defs) {
		jdbc.batchUpdate(EVENT_DEF_UPSERT, new BatchPreparedStatementSetter() {
			@Override
			public void setValues(PreparedStatement ps, int i) throws SQLException {
				EventDefSeen d = defs.get(i);
				ps.setLong(1, IdUtil.getSnowflakeNextId());
				ps.setString(2, d.appKey());
				ps.setString(3, d.eventName());
				ps.setString(4, d.tenantId());
			}

			@Override
			public int getBatchSize() {
				return defs.size();
			}
		});
	}

	/** epoch 毫秒 → TIMESTAMPTZ 绑定值（UTC，无时区歧义） */
	private static OffsetDateTime offsetTs(long epochMs) {
		return OffsetDateTime.ofInstant(Instant.ofEpochMilli(epochMs), ZoneOffset.UTC);
	}
}
