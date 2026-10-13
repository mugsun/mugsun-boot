package com.mugsun.boot.track;

import com.mugsun.boot.common.constant.TrackConstants;
import com.mugsun.boot.tenant.TenantContext;
import com.mugsun.boot.track.entity.TrackReplay;
import com.mugsun.boot.track.entity.TrackSession;
import com.mugsun.boot.track.mapper.TrackReplayMapper;
import com.mugsun.boot.track.mapper.TrackSessionMapper;
import com.mugsun.core.tool.exception.ServiceException;
import com.mybatisflex.core.paginate.Page;
import com.mybatisflex.core.query.QueryWrapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import javax.sql.DataSource;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.GZIPInputStream;

/**
 * 回放读取服务（/system/track/replay/** 数据源，G100）：类级 {@link TrackDS} 路由埋点库。
 * <p><b>租户隔离</b>：Flex mapper 查询，租户行级插件按会话上下文自动拼 tenant_id 条件
 * （超管「查看全部」模式不加条件；跨租户读取命中「不存在」），与 TrackAdminService 同语义。
 * <p><b>块内容读取</b>：按元数据行记录的存储坐标（platform/basePath + storage_key 目录 + seq 推导文件名）
 * 经 x-file-storage 下载 gzip 字节，服务端解压后以 application/json 明文返回 rrweb 事件数组——
 * 前端零解压依赖；写时已约束单块解压后 ≤1MB，读侧同界解压兜底。
 * <p><b>会话事件打点（G105）</b>：会话墙钟窗内事件流走 JdbcTemplate 原生 SQL（±1min 裁剪分区 +
 * T8 idx_event_session），租户条件显式拼接（与会话 Flex 查询同口径；TrackAnalysisService 模式）。
 * <p><b>纪律</b>：本类严禁调用 Sa-Token 权限校验/业务库 DAO（@TrackDS 范围内 DB 访问全落埋点库），
 * 权限校验与操作日志留痕由控制器在进入本类前完成。
 */
@Service
@TrackDS
public class TrackReplayQueryService {

	private static final Logger log = LoggerFactory.getLogger(TrackReplayQueryService.class);

	/** 读侧解压分块大小 */
	private static final int GZIP_CHUNK_BYTES = 8192;

	private final TrackReplayMapper replayMapper;
	private final TrackReplayStorage storage;
	private final TrackSessionMapper sessionMapper;
	private final JdbcTemplate jdbc;

	/** 注入全局路由数据源（FlexDataSource）：连接获取时按 {@code @TrackDS} 压栈路由埋点库（同 TrackAnalysisService 模式） */
	public TrackReplayQueryService(TrackReplayMapper replayMapper, TrackReplayStorage storage,
								   TrackSessionMapper sessionMapper, DataSource dataSource) {
		this.replayMapper = replayMapper;
		this.storage = storage;
		this.sessionMapper = sessionMapper;
		this.jdbc = new JdbcTemplate(dataSource);
	}

	/** 回放会话分页：start_time 倒序；appKey/hasError 可选过滤；投影剔除存储坐标（内部实现细节不下发） */
	public Page<Map<String, Object>> page(String appKey, Integer hasError, long pageNum, long pageSize) {
		String key = requireAppKey(appKey);
		QueryWrapper query = QueryWrapper.create().orderBy("start_time", false);
		query.eq("app_key", key);
		if (hasError != null) {
			query.eq("has_error", hasError == 1 ? 1 : 0);
		}
		Page<TrackReplay> page = replayMapper.paginate(pageNum, Math.min(pageSize, TrackConstants.QUERY_PAGE_SIZE_MAX), query);
		Map<String, Integer> sessionPageviews = sessionPageviews(key, page.getRecords());
		List<Map<String, Object>> records = new ArrayList<>(page.getRecords().size());
		for (TrackReplay replay : page.getRecords()) {
			records.add(project(replay, sessionPageviews.get(replay.getSessionId())));
		}
		return new Page<>(records, page.getPageNumber(), page.getPageSize(), page.getTotalRow());
	}

	/** 回放元数据 + 块清单（块键按 seq ∈ [0, last_seq] 纯推导，见 TrackReplayStorage javadoc） */
	public Map<String, Object> detail(String sessionId) {
		TrackReplay replay = requireReplay(sessionId);
		List<Map<String, Object>> blocks = new ArrayList<>();
		int lastSeq = replay.getLastSeq() == null ? -1 : replay.getLastSeq();
		for (int seq = 0; seq <= lastSeq; seq++) {
			Map<String, Object> block = new LinkedHashMap<>();
			block.put("seq", seq);
			block.put("key", TrackReplayStorage.deriveKey(replay.getStorageKey(), seq));
			blocks.add(block);
		}
		Map<String, Object> result = new LinkedHashMap<>();
		Map<String, Integer> sessionPageviews = sessionPageviews(replay.getAppKey(), List.of(replay));
		result.put("replay", project(replay, sessionPageviews.get(replay.getSessionId())));
		result.put("blocks", blocks);
		return result;
	}

	/**
	 * 读取块内容：rrweb 事件数组 JSON 明文字节（服务端已解压）。
	 * 对象不存在/平台下线/解压失败一律说明这一段读不出来（不暴露存储内部错误形态）。
	 */
	public byte[] blockJson(String sessionId, int seq) {
		TrackReplay replay = requireReplay(sessionId);
		int lastSeq = replay.getLastSeq() == null ? -1 : replay.getLastSeq();
		if (seq < 0 || seq > lastSeq) {
			throw new ServiceException("这一段回放对不上。请回到列表重新打开");
		}
		byte[] gz;
		try {
			gz = storage.load(replay.getStoragePlatform(), replay.getStorageBasePath(), replay.getStorageKey(), seq);
		} catch (Exception e) {
			log.warn("回放块读取失败 session={} seq={}：{}", sessionId, seq, e.getMessage());
			throw new ServiceException("这一段回放读不出来。请回到列表重新打开");
		}
		try {
			return gunzip(gz);
		} catch (IOException e) {
			log.warn("回放块解压失败 session={} seq={}：{}", sessionId, seq, e.getMessage());
			throw new ServiceException("这一段回放读不出来。请回到列表重新打开");
		}
	}

	/**
	 * 回放会话事件时间轴打点（G105）：会话墙钟窗（start−1m ~ coalesce(end, start)+1m 裁剪分区）内
	 * 按 received_at 升序返回 [{eventName, ts(epoch ms), urlPath}]，封顶
	 * {@value TrackConstants#REPLAY_SESSION_EVENTS_MAX} 条（防巨会话拉爆响应）。
	 * 会话不存在/跨租户 → 空数组（不报错；元信息非内容，本查询无审计留痕）。
	 */
	public List<Map<String, Object>> sessionEvents(String appKey, String sessionId) {
		appKey = requireAppKey(appKey);
		sessionId = requireSessionId(sessionId);
		// 会话归属校验（Flex 租户插件自动隔离：跨租户命中「不存在」→ 空数组，与事件查询租户口径一致）
		TrackSession session = sessionMapper.selectOneByQuery(QueryWrapper.create()
			.eq("app_key", appKey.trim()).eq("session_id", sessionId.trim()));
		if (session == null || session.getStartTime() == null) {
			return List.of();
		}
		// 墙钟窗 ±1min 裁剪分区（T8 idx_event_session 命中）：start/end 为 UTC 墙钟（TrackEventStore 写入规约），
		// 按 UTC 绑定与 received_at（TIMESTAMPTZ）比较口径一致，不依赖库会话时区
		OffsetDateTime from = session.getStartTime().minusMinutes(1).atOffset(ZoneOffset.UTC);
		OffsetDateTime to = (session.getEndTime() == null ? session.getStartTime() : session.getEndTime())
			.plusMinutes(1).atOffset(ZoneOffset.UTC);
		List<Object> args = new ArrayList<>(List.of(appKey.trim(), sessionId.trim(), from, to));
		String sql = "SELECT event_name, CAST(EXTRACT(EPOCH FROM ts) * 1000 AS BIGINT) AS ts_ms, url_path"
			+ " FROM track_event WHERE app_key = ? AND session_id = ?"
			+ " AND received_at >= ? AND received_at <= ?" + tenantFrag(currentTenant(), args)
			+ " ORDER BY received_at ASC LIMIT ?";
		args.add(TrackConstants.REPLAY_SESSION_EVENTS_MAX);
		List<Map<String, Object>> events = new ArrayList<>();
		jdbc.query(sql, rs -> {
			Map<String, Object> row = new LinkedHashMap<>();
			row.put("eventName", rs.getString("event_name"));
			row.put("ts", rs.getLong("ts_ms"));
			row.put("urlPath", rs.getString("url_path"));
			events.add(row);
		}, args.toArray());
		return events;
	}

	/** 会话标识空了或超长时说明回到列表重开。格式对但没有这条回放，仍走后面的「不存在」或空结果。 */
	private String requireSessionId(String sessionId) {
		if (sessionId == null || sessionId.isBlank() || sessionId.trim().length() > TrackConstants.SESSION_ID_MAX_LEN) {
			throw new ServiceException("这个会话对不上。请回到列表重新打开");
		}
		return sessionId.trim();
	}

	/** 按 session_id 取元数据（租户插件自动隔离；跨租户/已删 → 「不存在」） */
	private TrackReplay requireReplay(String sessionId) {
		sessionId = requireSessionId(sessionId);
		TrackReplay replay = replayMapper.selectOneByQuery(QueryWrapper.create().eq("session_id", sessionId));
		if (replay == null || replay.getStorageKey() == null) {
			throw new ServiceException("这个回放没有了。请回到列表重新打开");
		}
		return replay;
	}

	/**
	 * 同会话事件流里的页面浏览数（track_session.pageviews，按 $pageview 累加；限当前租户、未逻辑删除）。
	 * rrweb 只在整页加载时产生全量快照，SPA 路由切换（pushState/replaceState/popstate）不产生快照，
	 * 但 SDK pageview 插件对每次路由切换都会上报 $pageview，所以用它补足 SPA 会话的页面数。
	 */
	private Map<String, Integer> sessionPageviews(String appKey, List<TrackReplay> replays) {
		List<String> sessionIds = new ArrayList<>();
		for (TrackReplay replay : replays) {
			if (replay.getSessionId() != null) {
				sessionIds.add(replay.getSessionId());
			}
		}
		Map<String, Integer> result = new HashMap<>();
		if (sessionIds.isEmpty() || appKey == null) {
			return result;
		}
		// 显式租户条件 + 逻辑删除过滤（与本类 sessionEvents 同口径，不依赖插件隐式拼接）：
		// 跨租户同 session_id 的会话行、已逻辑删除的会话行都不得参与页面数
		List<Object> args = new ArrayList<>();
		args.add(appKey);
		StringBuilder sql = new StringBuilder("SELECT session_id, pageviews FROM track_session"
			+ " WHERE app_key = ? AND is_deleted = 0 AND session_id IN (");
		for (int i = 0; i < sessionIds.size(); i++) {
			sql.append(i == 0 ? "?" : ", ?");
			args.add(sessionIds.get(i));
		}
		sql.append(')').append(tenantFrag(currentTenant(), args));
		jdbc.query(sql.toString(), rs -> {
			int pageviews = rs.getInt("pageviews");
			if (!rs.wasNull()) {
				result.put(rs.getString("session_id"), pageviews);
			}
		}, args.toArray());
		return result;
	}

	/**
	 * 回放页面数：全量快照数（整页加载）与会话 $pageview 数取大。
	 * 纯 SPA 会话快照恒为 1，取 $pageview 数；整页跳转的会话两者一致；事件流缺失时退回快照数。
	 */
	static int resolvePageCount(Integer snapshotPages, Integer sessionPageviews) {
		int snapshots = snapshotPages == null ? 0 : snapshotPages;
		int pageviews = sessionPageviews == null ? 0 : sessionPageviews;
		return Math.max(snapshots, pageviews);
	}

	/** 响应投影（startTime 转 epoch 毫秒 UTC；storage_key/平台坐标为内部实现细节，不下发前端） */
	private Map<String, Object> project(TrackReplay replay, Integer sessionPageviews) {
		Map<String, Object> row = new LinkedHashMap<>();
		row.put("id", replay.getId());
		row.put("sessionId", replay.getSessionId());
		row.put("appKey", replay.getAppKey());
		row.put("tenantId", replay.getTenantId());
		row.put("distinctId", replay.getDistinctId());
		row.put("userId", replay.getUserId());
		row.put("startTime", replay.getStartTime() == null ? null : replay.getStartTime().toEpochSecond(ZoneOffset.UTC) * 1000);
		row.put("durationMs", replay.getDurationMs());
		row.put("pageCount", resolvePageCount(replay.getPageCount(), sessionPageviews));
		row.put("rrwebEvents", replay.getRrwebEvents());
		row.put("sizeBytes", replay.getSizeBytes());
		row.put("hasError", replay.getHasError());
		row.put("entryPath", replay.getEntryPath());
		row.put("lastSeq", replay.getLastSeq());
		// T5 墙钟锚点（epoch 毫秒；回放打点条定位基准，NULL 由前端兜底）
		row.put("firstEventTs", replay.getFirstEventTs());
		row.put("lastEventTs", replay.getLastEventTs());
		return row;
	}

	/** 当前请求租户（null=超管查看全部；与会话 Flex 查询租户插件同口径，无上下文 fail-closed 抛异常） */
	private String currentTenant() {
		Object[] ids = TenantContext.resolveTenantIds();
		return ids == null ? null : String.valueOf(ids[0]);
	}

	/** 租户条件片段：有租户 → 拼 {@code AND tenant_id = ?} 并把值入参；null（查看全部）→ 空串（与 Flex 租户插件同口径） */
	private String tenantFrag(String tenant, List<Object> args) {
		if (tenant == null) {
			return "";
		}
		args.add(tenant);
		return " AND tenant_id = ?";
	}

	private String requireAppKey(String appKey) {
		if (appKey == null || appKey.isBlank()) {
			throw new ServiceException("请先选择应用。回放是按应用分开的");
		}
		String key = appKey.trim();
		if (key.length() > TrackConstants.APP_KEY_MAX_LEN) {
			throw new ServiceException("应用标识不对。请从应用列表里重新选择");
		}
		// 原始 SQL 不看租户：完全没有这个应用才说明「不存在」。
		// 别的租户有同名应用时仍返回标识，列表由租户插件滤成空页。
		Long found = jdbc.queryForObject(
			"SELECT count(*) FROM track_app WHERE app_key = ? AND is_deleted = 0", Long.class, key);
		if (found == null || found == 0) {
			throw new ServiceException("这个应用不存在。请刷新应用列表后再看");
		}
		return key;
	}

	/** 有界解压（写时已约束 ≤{@value TrackConstants#REPLAY_BLOCK_MAX_BYTES}，读侧同界兜底） */
	private byte[] gunzip(byte[] raw) throws IOException {
		try (GZIPInputStream gzip = new GZIPInputStream(new ByteArrayInputStream(raw));
			 ByteArrayOutputStream out = new ByteArrayOutputStream()) {
			byte[] chunk = new byte[GZIP_CHUNK_BYTES];
			int total = 0;
			int n;
			while ((n = gzip.read(chunk)) != -1) {
				total += n;
				if (total > TrackConstants.REPLAY_BLOCK_MAX_BYTES) {
					throw new IOException("回放块解压后超界");
				}
				out.write(chunk, 0, n);
			}
			return out.toByteArray();
		}
	}
}
