package com.mugsun.boot.track;

import cn.hutool.core.util.IdUtil;
import org.dromara.x.file.storage.core.FileInfo;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;

/**
 * 回放元数据存储（PG 实现，{@code @TrackDS} 路由埋点独立库）：消费侧落库唯一出口。
 * <p>刻意用 JdbcTemplate 原生 SQL（同 {@link TrackEventStore} 理由）：消费线程跨租户混合、无会话上下文，
 * 原生 SQL 不经 MyBatis 拦截器，天然绕开租户行级插件（调用方 {@code TenantContext.ignore} 双保险，
 * 每行 tenant_id 显式自带）；upsert 语义写死在 SQL（累加/GREATEST/COALESCE 回填），乱序与重试安全。
 * <p><b>track_replay upsert 语义</b>（session_id 唯一，首块建行、后续块并入）：
 * start_time/storage_key/storage_platform/storage_base_path 取首块（冲突不覆盖）；
 * page_count/rrweb_events/size_bytes 累加；last_seq 取 GREATEST；
 * duration_ms 为墙钟口径（first_event_ts LEAST / last_event_ts GREATEST 归并后取极差，与播放器时间轴一致）；
 * distinct_id 空串回填、user_id/entry_path COALESCE 回填、has_error GREATEST 置位——
 * 回放块可能先于事件流会话落库（会话行尚无时身份/入口为空口径），后续块到达时从会话快照回填。
 * <p><b>track_session.has_replay</b>：GREATEST 置位不回退；会话行不存在时先落占位行
 * （start/end=首块到达时刻，后续事件 upsert 按 LEAST/GREATEST 自愈）。
 */
@Component
@TrackDS
public class TrackReplayStore {

	/** 回放元数据 upsert：首块建行（start_time/存储坐标取首块），后续块累加 + 置位 + 空值回填；
	 * 时长为墙钟口径——first_event_ts 取 LEAST、last_event_ts 取 GREATEST，duration_ms = 末-首（与播放器时间轴一致） */
	private static final String REPLAY_UPSERT = "INSERT INTO track_replay (id, session_id, app_key, tenant_id,"
		+ " distinct_id, user_id, start_time, duration_ms, page_count, rrweb_events, size_bytes, has_error,"
		+ " entry_path, storage_key, last_seq, storage_platform, storage_base_path, first_event_ts, last_event_ts,"
		+ " create_time, update_time, is_deleted)"
		+ " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, now(), now(), 0)"
		+ " ON CONFLICT (session_id) WHERE is_deleted = 0 DO UPDATE SET"
		+ " first_event_ts = LEAST(COALESCE(track_replay.first_event_ts, EXCLUDED.first_event_ts), EXCLUDED.first_event_ts),"
		+ " last_event_ts = GREATEST(COALESCE(track_replay.last_event_ts, EXCLUDED.last_event_ts), EXCLUDED.last_event_ts),"
		+ " duration_ms = GREATEST(0, (GREATEST(COALESCE(track_replay.last_event_ts, EXCLUDED.last_event_ts),"
		+ " EXCLUDED.last_event_ts) - LEAST(COALESCE(track_replay.first_event_ts, EXCLUDED.first_event_ts),"
		+ " EXCLUDED.first_event_ts))::int),"
		+ " page_count = track_replay.page_count + EXCLUDED.page_count,"
		+ " rrweb_events = track_replay.rrweb_events + EXCLUDED.rrweb_events,"
		+ " size_bytes = track_replay.size_bytes + EXCLUDED.size_bytes,"
		+ " last_seq = GREATEST(track_replay.last_seq, EXCLUDED.last_seq),"
		+ " distinct_id = CASE WHEN track_replay.distinct_id = '' THEN EXCLUDED.distinct_id ELSE track_replay.distinct_id END,"
		+ " user_id = COALESCE(track_replay.user_id, EXCLUDED.user_id),"
		+ " entry_path = COALESCE(track_replay.entry_path, EXCLUDED.entry_path),"
		+ " has_error = GREATEST(track_replay.has_error, EXCLUDED.has_error),"
		+ " update_time = now()";

	/** 会话回放置位：has_replay GREATEST(1) 不回退；会话行不存在落占位行（distinct_id 空串，事件流到达后自愈） */
	private static final String SESSION_MARK_REPLAY = "INSERT INTO track_session (id, session_id, app_key, tenant_id,"
		+ " distinct_id, start_time, end_time, has_replay, create_time, update_time, is_deleted)"
		+ " VALUES (?, ?, ?, ?, ?, ?, ?, 1, now(), now(), 0)"
		+ " ON CONFLICT (session_id) WHERE is_deleted = 0 DO UPDATE SET"
		+ " has_replay = GREATEST(track_session.has_replay, 1), update_time = now()";

	/** 会话快照查询（回放元数据的身份/入口/错误标记来源：事件流已裁定的服务端口径） */
	private static final String SESSION_SNAPSHOT = "SELECT distinct_id, user_id, tenant_id, entry_path, has_error"
		+ " FROM track_session WHERE session_id = ? AND is_deleted = 0";

	private final JdbcTemplate jdbc;

	/** 注入全局路由数据源（连接获取时按 {@code @TrackDS} 压栈的 DataSourceKey 路由埋点库） */
	public TrackReplayStore(DataSource dataSource) {
		this.jdbc = new JdbcTemplate(dataSource);
	}

	/** 会话快照（distinctId/userId/tenantId/entryPath/hasError；会话行不存在返回 null 由各调用方兜底） */
	public SessionSnapshot snapshotOfSession(String sessionId) {
		List<SessionSnapshot> rows = jdbc.query(SESSION_SNAPSHOT, (rs, i) -> new SessionSnapshot(
			rs.getString("distinct_id"),
			rs.getObject("user_id") == null ? null : rs.getLong("user_id"),
			rs.getString("tenant_id"),
			rs.getString("entry_path"),
			rs.getInt("has_error")), sessionId);
		return rows.isEmpty() ? null : rows.get(0);
	}

	/**
	 * 落一块：会话置位在前（GREATEST 幂等），元数据 upsert 在后（累加语义，仅执行一次——
	 * 失败后整批重试时前序步骤幂等，杜绝累加列双计）。
	 *
	 * @param block    回放块（已落对象存储）
	 * @param stored   对象存储回执（platform/basePath/path/filename）
	 * @param snapshot 会话快照（可空 = 会话行尚未建立，身份/入口空口径待后续块回填）
	 */
	public void persistBlock(TrackReplayBlock block, FileInfo stored, SessionSnapshot snapshot) {
		LocalDateTime receivedAt = localTs(block.getReceivedAtMs());
		String distinctId = snapshot != null && snapshot.distinctId() != null ? snapshot.distinctId() : "";
		Long userId = snapshot == null ? null : snapshot.userId();
		String entryPath = snapshot == null ? null : snapshot.entryPath();
		int hasError = snapshot == null ? 0 : snapshot.hasError();
		// 租户裁定：会话已建立取会话租户（与事件流 token 裁定同口径），否则用摄入侧 app_key 映射租户
		String tenantId = snapshot != null && snapshot.tenantId() != null && !snapshot.tenantId().isBlank()
			? snapshot.tenantId() : block.getTenantId();

		jdbc.update(SESSION_MARK_REPLAY, IdUtil.getSnowflakeNextId(), block.getSessionId(), block.getAppKey(),
			tenantId, distinctId, receivedAt, receivedAt);

		jdbc.update(REPLAY_UPSERT, ps -> {
			ps.setLong(1, IdUtil.getSnowflakeNextId());
			ps.setString(2, block.getSessionId());
			ps.setString(3, block.getAppKey());
			ps.setString(4, tenantId);
			ps.setString(5, distinctId);
			if (userId == null) {
				ps.setNull(6, Types.BIGINT);
			} else {
				ps.setLong(6, userId);
			}
			ps.setTimestamp(7, Timestamp.valueOf(receivedAt));
			// duration_ms 墙钟口径 = 末事件-首事件（INT 列钳制防溢出）；冲突侧由 SQL 按 LEAST/GREATEST 重算
			ps.setInt(8, (int) Math.min(Math.max(0, block.getLastEventTs() - block.getFirstEventTs()),
				Integer.MAX_VALUE));
			ps.setInt(9, block.getPageCount());
			ps.setInt(10, block.getRrwebEvents());
			// 体积展示口径 = 压缩后真实字节（对象存储占用；累计上限控制走解压后口径，见摄入服务）
			ps.setLong(11, block.getGzBytes().length);
			ps.setInt(12, hasError);
			ps.setString(13, entryPath);
			ps.setString(14, stored.getBasePath() + stored.getPath() + stored.getFilename());
			ps.setInt(15, block.getSeq());
			ps.setString(16, stored.getPlatform());
			ps.setString(17, stored.getBasePath());
			ps.setLong(18, block.getFirstEventTs());
			ps.setLong(19, block.getLastEventTs());
		});
	}

	/** 会话快照（track_session 行投影；身份/租户均事件流服务端裁定口径） */
	public record SessionSnapshot(String distinctId, Long userId, String tenantId, String entryPath, int hasError) {
	}

	/** epoch 毫秒 → UTC 墙钟（TIMESTAMP 列绑定规约，与 TrackEventStore 同口径） */
	private static LocalDateTime localTs(long epochMs) {
		return LocalDateTime.ofInstant(Instant.ofEpochMilli(epochMs), ZoneOffset.UTC);
	}
}
