package com.mugsun.boot.track;

import com.mugsun.boot.common.constant.TrackConstants;
import com.mugsun.boot.system.service.ParamService;
import com.mugsun.boot.tenant.TenantContext;
import com.mugsun.core.tool.exception.ServiceException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import javax.sql.DataSource;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.GZIPInputStream;

/**
 * 用户细查时间线服务（/system/track/user/** 数据源，G102）：类级 {@link TrackDS} 路由埋点库。
 * <p><b>身份归并</b>：userId 查询时 LEFT JOIN track_identity（uk_identity 部分唯一索引保证每事件至多一行映射，
 * JOIN 不增行），把该 user 全部 distinct_id 的匿名期行为并上（coalesce 口径：登录段走 e.user_id 直中，
 * 匿名段走 m.user_id 映射）；distinctId 直查不带归并（访客口径）。
 * <p><b>游标分页</b>：按 (received_at, id) 倒序游标（防 offset 跳变——时间线持续写入，offset 分页会重复/漏行）；
 * 范围硬限 ≤{@value TrackConstants#TIMELINE_RANGE_MAX_MS}ms（7 天），索引 idx_event_user_timeline /
 * idx_event_distinct_timeline 裁剪（T7）。
 * <p><b>租户隔离</b>：JdbcTemplate 原生 SQL + 显式租户条件（与 TrackAnalysisService 同语义：
 * null=超管查看全部；无上下文 fail-closed）。
 * <p><b>纪律</b>：本类严禁调用 Sa-Token 权限校验/业务库 DAO（@TrackDS 范围内 DB 访问全落埋点库），
 * 权限校验与操作日志留痕由控制器在进入本类前完成。
 */
@Service
@TrackDS
public class TrackUserTimelineService {

	private static final Logger log = LoggerFactory.getLogger(TrackUserTimelineService.class);

	/** 读侧解压分块大小 */
	private static final int GZIP_CHUNK_BYTES = 8192;

	/** 时间线投影列（recvMs/rowId 为游标内部列，下发前剔除） */
	private static final String TIMELINE_SELECT = "SELECT e.event_id AS \"eventId\", e.event_name AS \"eventName\","
		+ " CAST(EXTRACT(EPOCH FROM e.ts) * 1000 AS BIGINT) AS \"ts\","
		+ " CAST(EXTRACT(EPOCH FROM e.client_ts) * 1000 AS BIGINT) AS \"clientTs\","
		+ " e.url_path AS \"urlPath\", e.route_path AS \"routePath\", e.duration_ms AS \"durationMs\","
		+ " e.session_id AS \"sessionId\", e.props::text AS \"props\","
		+ " coalesce(s.has_replay, 0) AS \"hasReplay\","
		+ " (e.props->>'" + TrackConstants.PROP_BODY_REF + "') IS NOT NULL AS \"hasApiBody\","
		+ " CAST(EXTRACT(EPOCH FROM e.received_at) * 1000 AS BIGINT) AS \"recvMs\", e.id AS \"rowId\""
		+ " FROM track_event e"
		+ " LEFT JOIN track_session s ON s.session_id = e.session_id AND s.is_deleted = 0";

	/** 身份归并 JOIN 片段（表别名固定 e/m；uk_identity 唯一保证每事件至多一行，不增行） */
	private static final String IDENTITY_JOIN = " LEFT JOIN track_identity m"
		+ " ON m.app_key = e.app_key AND m.distinct_id = e.distinct_id AND m.is_deleted = 0";

	private final JdbcTemplate jdbc;
	private final TrackApiBodyStorage storage;
	private final ParamService paramService;

	public TrackUserTimelineService(DataSource dataSource, TrackApiBodyStorage storage, ParamService paramService) {
		this.jdbc = new JdbcTemplate(dataSource);
		this.storage = storage;
		this.paramService = paramService;
	}

	/**
	 * 行为时间线游标分页：received_at+id 倒序；userId 优先（identity 归并匿名期 distinct_id 行为），
	 * distinctId 为访客直查口径。返回 {records, nextCursor}——nextCursor 非空即还有下一页。
	 * 行结构：{eventId, eventName, ts, clientTs, urlPath, routePath, durationMs, sessionId,
	 * props（JSON 原文字符串，前端自解析）, hasReplay（经 track_session 标记）, hasApiBody（props 含 body_ref 即 true）}。
	 */
	public Map<String, Object> timeline(String appKey, Long userId, String distinctId,
										Long startTs, Long endTs, String cursor, Integer pageSize) {
		assertAppKey(appKey);
		if (userId == null && (distinctId == null || distinctId.isBlank())) {
			throw new ServiceException("缺少 userId 或 distinctId");
		}
		if (distinctId != null && distinctId.length() > TrackConstants.DISTINCT_ID_MAX_LEN) {
			throw new ServiceException("非法 distinctId");
		}
		if (startTs == null || endTs == null || startTs < 0 || endTs <= startTs) {
			throw new ServiceException("缺少或非法时间范围（startTs/endTs，epoch 毫秒）");
		}
		if (endTs - startTs > TrackConstants.TIMELINE_RANGE_MAX_MS) {
			throw new ServiceException("时间范围不能超过 7 天");
		}
		long size = clampPageSize(pageSize);
		// 游标解析：{receivedAtMs}_{rowId}（上一页末行位置；非法游标 400，防错位翻页）
		OffsetDateTime cursorTs = null;
		Long cursorId = null;
		if (cursor != null && !cursor.isBlank()) {
			int sep = cursor.indexOf('_');
			try {
				cursorTs = OffsetDateTime.ofInstant(Instant.ofEpochMilli(Long.parseLong(cursor.substring(0, sep))), ZoneOffset.UTC);
				cursorId = Long.parseLong(cursor.substring(sep + 1));
			} catch (RuntimeException e) {
				throw new ServiceException("非法 cursor");
			}
		}

		String tenant = currentTenant();
		List<Object> args = new ArrayList<>();
		StringBuilder sql = new StringBuilder(TIMELINE_SELECT);
		if (userId != null) {
			sql.append(IDENTITY_JOIN);
		}
		sql.append(" WHERE e.app_key = ?");
		args.add(appKey.trim());
		if (userId != null) {
			sql.append(" AND (e.user_id = ? OR m.user_id = ?)");
			args.add(userId);
			args.add(userId);
		} else {
			sql.append(" AND e.distinct_id = ?");
			args.add(distinctId.trim());
		}
		sql.append(" AND e.received_at >= ? AND e.received_at <= ?");
		args.add(OffsetDateTime.ofInstant(Instant.ofEpochMilli(startTs), ZoneOffset.UTC));
		args.add(OffsetDateTime.ofInstant(Instant.ofEpochMilli(endTs), ZoneOffset.UTC));
		sql.append(tenantFrag("e", tenant, args));
		if (cursorTs != null) {
			sql.append(" AND (e.received_at, e.id) < (?, ?)");
			args.add(cursorTs);
			args.add(cursorId);
		}
		sql.append(" ORDER BY e.received_at DESC, e.id DESC LIMIT ?");
		args.add(size + 1);

		List<Map<String, Object>> rows = jdbc.queryForList(sql.toString(), args.toArray());
		boolean hasMore = rows.size() > size;
		if (hasMore) {
			rows = new ArrayList<>(rows.subList(0, (int) size));
		}
		String nextCursor = null;
		if (hasMore && !rows.isEmpty()) {
			Map<String, Object> last = rows.get(rows.size() - 1);
			nextCursor = last.get("recvMs") + "_" + last.get("rowId");
		}
		List<Map<String, Object>> records = new ArrayList<>(rows.size());
		for (Map<String, Object> row : rows) {
			row.remove("recvMs");
			row.remove("rowId");
			records.add(row);
		}
		Map<String, Object> result = new LinkedHashMap<>();
		result.put("records", records);
		result.put("nextCursor", nextCursor);
		return result;
	}

	/**
	 * 读取接口响应体明文（api-body 端点用）：按 event_id 查事件取 props.body_ref 推导对象键
	 * （api-body/{app_key}/{yyyyMM}/{event_id}.json.gz，yyyyMM 取事件 received_at 月），
	 * x-file-storage 读出后服务端解压直发明文。事件不存在/无 body_ref/对象已清理一律
	 * 「body 未采集或已清理」（400，不暴露存储内部错误形态）。
	 */
	public byte[] bodyJson(String eventId) {
		if (eventId == null || eventId.isBlank() || eventId.length() > TrackConstants.EVENT_ID_MAX_LEN
			|| !TrackConstants.REPLAY_PATH_SAFE.matcher(eventId.trim()).matches()) {
			throw new ServiceException("无效 eventId");
		}
		String tenant = currentTenant();
		List<Object> args = new ArrayList<>(List.of(eventId.trim()));
		List<Map<String, Object>> rows = jdbc.queryForList(
			"SELECT e.app_key, e.received_at, e.props->>'" + TrackConstants.PROP_BODY_REF + "' AS ref"
				+ " FROM track_event e WHERE e.event_id = ?" + tenantFrag("e", tenant, args) + " LIMIT 1",
			args.toArray());
		if (rows.isEmpty() || rows.get(0).get("ref") == null) {
			throw new ServiceException("body 未采集或已清理");
		}
		Map<String, Object> row = rows.get(0);
		long receivedAtMs = toEpochMs(row.get("received_at"));
		byte[] gz;
		try {
			gz = storage.load((String) row.get("app_key"), receivedAtMs, (String) row.get("ref"));
		} catch (Exception e) {
			log.warn("响应体读取失败 event={}：{}", eventId, e.getMessage());
			throw new ServiceException("body 未采集或已清理");
		}
		// 有界解压兜底（写时已按 sys_param 上限约束；读侧取参数与兜底默认的较大者，防参数调小后误杀存量）
		long maxBytes = Math.max(paramLong(TrackConstants.PARAM_API_BODY_MAX_BYTES,
			TrackConstants.DEFAULT_API_BODY_MAX_BYTES), TrackConstants.DEFAULT_API_BODY_MAX_BYTES);
		try {
			return gunzip(gz, maxBytes);
		} catch (IOException e) {
			log.warn("响应体解压失败 event={}：{}", eventId, e.getMessage());
			throw new ServiceException("body 未采集或已清理");
		}
	}

	// ==================== 内部工具 ====================

	/** 当前请求租户（null=超管查看全部；无上下文 fail-closed 抛异常，与 Flex 租户插件同语义） */
	private String currentTenant() {
		Object[] ids = TenantContext.resolveTenantIds();
		return ids == null ? null : String.valueOf(ids[0]);
	}

	/** 租户条件片段：有租户 → 拼 {@code AND [alias.]tenant_id = ?} 并把值入参；null（查看全部）→ 空串 */
	private String tenantFrag(String alias, String tenant, List<Object> args) {
		if (tenant == null) {
			return "";
		}
		args.add(tenant);
		return " AND " + (alias == null ? "" : alias + ".") + "tenant_id = ?";
	}

	private void assertAppKey(String appKey) {
		if (appKey == null || appKey.isBlank() || appKey.length() > TrackConstants.APP_KEY_MAX_LEN) {
			throw new ServiceException("缺少或非法 appKey");
		}
	}

	private long clampPageSize(Integer pageSize) {
		if (pageSize == null || pageSize < 1) {
			return TrackConstants.TIMELINE_DEFAULT_PAGE_SIZE;
		}
		return Math.min(pageSize, TrackConstants.TIMELINE_PAGE_SIZE_MAX);
	}

	/** TIMESTAMPTZ 列 → epoch 毫秒（驱动按配置可能给 Timestamp 或 OffsetDateTime，统一收口） */
	private long toEpochMs(Object value) {
		if (value instanceof Timestamp ts) {
			return ts.toInstant().toEpochMilli();
		}
		if (value instanceof OffsetDateTime odt) {
			return odt.toInstant().toEpochMilli();
		}
		throw new ServiceException("事件时间字段异常");
	}

	/** 有界解压（写时已按 sys_param 上限约束，读侧同界兜底防 zip 炸弹） */
	private byte[] gunzip(byte[] raw, long maxBytes) throws IOException {
		try (GZIPInputStream gzip = new GZIPInputStream(new ByteArrayInputStream(raw));
			 ByteArrayOutputStream out = new ByteArrayOutputStream()) {
			byte[] chunk = new byte[GZIP_CHUNK_BYTES];
			long total = 0;
			int n;
			while ((n = gzip.read(chunk)) != -1) {
				total += n;
				if (total > maxBytes) {
					throw new IOException("响应体解压后超界");
				}
				out.write(chunk, 0, n);
			}
			return out.toByteArray();
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
