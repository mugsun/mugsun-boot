package com.mugsun.boot.track;

import com.alicp.jetcache.anno.CacheType;
import com.alicp.jetcache.anno.Cached;
import com.mugsun.boot.common.constant.TrackConstants;
import com.mugsun.boot.track.job.TrackVitalsJob;
import com.mugsun.boot.tenant.TenantContext;
import com.mugsun.core.tool.exception.ServiceException;
import com.mybatisflex.core.paginate.Page;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.domain.Range;
import org.springframework.data.redis.connection.Limit;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import javax.sql.DataSource;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 埋点分析查询服务（/system/track/** 数据源）：看板读侧全部走 track 独立库。
 * <p><b>租户隔离</b>：管理分页（app/event-def）走 Flex mapper，租户行级插件自动拼 tenant_id 条件；
 * 本类的聚合查询（FILTER/直方图/身份归并 JOIN 超出 QueryWrapper 表达力）用 JdbcTemplate 原生 SQL +
 * <b>显式租户条件</b>（{@link #currentTenant()} 与插件同语义：null=超管查看全部；无上下文 fail-closed 抛
 * {@code TenantException}）。Redis 通道（实时流/在线人数）无 SQL 可拼，先按 appKey 校验本租户可见性再读。
 * <p><b>今日/历史界限</b>：overview 卡片 = 历史（昨日及更早，查 stats_day）+ 今日（当日分区直算明细）。
 * UV 为区间明细精确去重（口径 {@link TrackConstants#UV_EXPR}，与事件分析一致）。
 * <p><b>缓存</b>：overview/trend/pages/vitals 走 JetCache LOCAL {@value TrackConstants#ANALYSIS_CACHE_SECONDS}s
 * （tenantKeyConvertor 自动加租户前缀，缓存层租户隔离天然成立）；自注入代理解 AOP（ParamService 同款）。
 */
@Service
@TrackDS
public class TrackAnalysisService {

	private static final Logger log = LoggerFactory.getLogger(TrackAnalysisService.class);

	private final JdbcTemplate jdbc;
	private final StringRedisTemplate redis;
	private final ObjectProvider<TrackAnalysisService> self;
	private final TrackTimezoneProperties timezones;

	public TrackAnalysisService(DataSource dataSource, StringRedisTemplate redis,
								ObjectProvider<TrackAnalysisService> self, TrackTimezoneProperties timezones) {
		this.timezones = timezones;
		this.jdbc = new JdbcTemplate(dataSource);
		this.redis = redis;
		this.self = self;
	}

	// ==================== 概览 ====================

	/** 概览卡片 + 来源/设备/浏览器分布（缓存 {@value TrackConstants#ANALYSIS_CACHE_SECONDS}s） */
	public Map<String, Object> overview(String appKey, Integer days) {
		appKey = assertAppKey(appKey);
		int d = assertDays(days);
		String tenant = currentTenant();
		return self.getObject().cachedOverview(appKey, d, tenant, timezones.zoneFor(tenant).getId());
	}

	@Cached(name = "track:ana:ov:", key = "#appKey + ':' + #days + ':' + #tenant + ':' + #zoneId",
		expire = TrackConstants.ANALYSIS_CACHE_SECONDS, cacheType = CacheType.LOCAL)
	public Map<String, Object> cachedOverview(String appKey, int days, String tenant, String zoneId) {
		// 按租户时区切日：「今天」从当地 0 点开始
		TrackDayWindow w = TrackDayWindow.of(ZoneId.of(zoneId), Instant.now(), days);
		LocalDate today = w.today();
		LocalDate from = w.fromDate();
		LocalDate yesterday = today.minusDays(1);

		Map<String, Object> hist;
		Map<String, Object> todayRow;
		if (w.isUtc()) {
			// 历史部分（昨日及更早）：stats_day overview 行累加
			List<Object> histArgs = new ArrayList<>(List.of(appKey, from, yesterday));
			hist = queryOne(
				"SELECT coalesce(sum(pv), 0) AS pv, coalesce(sum(uv), 0) AS uv,"
					+ " coalesce(sum(session_count), 0) AS sc, coalesce(sum(event_count), 0) AS ec"
					+ " FROM track_stats_day WHERE app_key = ? AND dim_type = '" + TrackConstants.DIM_OVERVIEW + "'"
					+ " AND stat_date >= ? AND stat_date <= ?" + tenantFrag(null, tenant, histArgs),
				histArgs);

			// 今日部分：当日分区直算
			OffsetDateTime todayStart = today.atStartOfDay().atOffset(ZoneOffset.UTC);
			List<Object> todayArgs = new ArrayList<>(List.of(appKey, todayStart));
			todayRow = queryOne(
				"SELECT count(*) FILTER (WHERE e.event_name = '" + TrackConstants.EVENT_PAGEVIEW + "') AS pv,"
					+ " count(*) AS ec, count(DISTINCT e.session_id) AS sc,"
					+ " " + TrackConstants.UV_EXPR + " AS uv"
					+ " FROM track_event e"
					+ " WHERE e.app_key = ? AND e.received_at >= ?" + tenantFrag("e", tenant, todayArgs),
				todayArgs);
		} else {
			// 非 UTC：stats_day 按 UTC 日汇总无法改切，窗口内明细直算；sessionCount = 各当地日活跃会话数之和（与日表累加同口径）
			hist = Map.of();
			List<Object> rawArgs = new ArrayList<>(List.of(zoneId, appKey, w.fromOffset()));
			todayRow = queryOne(
				"SELECT count(*) FILTER (WHERE e.event_name = '" + TrackConstants.EVENT_PAGEVIEW + "') AS pv,"
					+ " count(*) AS ec, count(DISTINCT ((e.received_at AT TIME ZONE ?)::date, e.session_id)) AS sc"
					+ " FROM track_event e"
					+ " WHERE e.app_key = ? AND e.received_at >= ?" + tenantFrag("e", tenant, rawArgs),
				rawArgs);
		}

		// 会话卡：全会话表区间直算（行数比事件少 1-2 个数量级）；仅已结算会话口径（duration/bounce 定稿值）
		LocalDateTime rangeStart = w.fromUtcWall();
		List<Object> sessArgs = new ArrayList<>(List.of(appKey, rangeStart));
		Map<String, Object> sess = queryOne(
			"SELECT count(*) AS total, count(*) FILTER (WHERE pageviews <= 1) AS bounce,"
				+ " coalesce(round(avg(duration_ms)), 0)::bigint AS \"avgDur\""
				+ " FROM track_session WHERE app_key = ? AND start_time >= ? AND settled = 1 AND is_deleted = 0"
				+ tenantFrag(null, tenant, sessArgs),
			sessArgs);

		long pv = num(hist, "pv") + num(todayRow, "pv");
		// UV 区间精确去重（与事件分析同口径同时间窗；不再用日 UV 累加，否则跨日重访被重复计）
		List<Object> uvArgs = new ArrayList<>(List.of(appKey, w.fromOffset()));
		Map<String, Object> uvRow = queryOne("SELECT " + TrackConstants.UV_EXPR + " AS uv FROM track_event e"
			+ " WHERE e.app_key = ? AND e.received_at >= ?" + tenantFrag("e", tenant, uvArgs), uvArgs);
		long uv = num(uvRow, "uv");
		long sessionCount = num(hist, "sc") + num(todayRow, "sc");
		long eventCount = num(hist, "ec") + num(todayRow, "ec");
		long settledSessions = num(sess, "total");
		long bounce = num(sess, "bounce");
		double bounceRate = settledSessions == 0 ? 0.0
			: Math.round(bounce * 10000.0 / settledSessions) / 10000.0;

		Map<String, Object> cards = new LinkedHashMap<>();
		cards.put("pv", pv);
		cards.put("uv", uv);
		cards.put("sessionCount", sessionCount);
		cards.put("eventCount", eventCount);
		cards.put("avgSessionDurationMs", num(sess, "avgDur"));
		cards.put("bounceRate", bounceRate);
		// 已结算会话数：0 表示时长/跳出率尚未定稿（会话静默 30 分钟才结算），前端据此显示占位而非 0
		cards.put("settledSessionCount", settledSessions);

		Map<String, Object> result = new LinkedHashMap<>();
		result.put("cards", cards);
		result.put("referrerDist", distribution(appKey, rangeStart, tenant,
			"coalesce(nullif(referrer_domain, ''), '" + TrackConstants.DIM_REFERRER_DIRECT + "')"));
		result.put("deviceDist", distribution(appKey, rangeStart, tenant,
			"coalesce(nullif(device, ''), '" + TrackConstants.DIM_UNKNOWN + "')"));
		result.put("browserTop", distribution(appKey, rangeStart, tenant,
			"coalesce(nullif(browser, ''), '" + TrackConstants.DIM_UNKNOWN + "')"));
		return result;
	}

	/** 分布统计（PV 口径，Top {@value TrackConstants#ANALYSIS_TOP_LIMIT}）：范围 = [from, 当前]，明细分区裁剪直算 */
	private List<Map<String, Object>> distribution(String appKey, LocalDateTime from, String tenant, String dimExpr) {
		List<Object> args = new ArrayList<>(List.of(appKey, from.atOffset(ZoneOffset.UTC)));
		String sql = "SELECT " + dimExpr + " AS \"name\","
			+ " count(*) FILTER (WHERE event_name = '" + TrackConstants.EVENT_PAGEVIEW + "') AS \"value\""
			+ " FROM track_event WHERE app_key = ? AND received_at >= ?" + tenantFrag(null, tenant, args)
			+ " GROUP BY 1 ORDER BY \"value\" DESC, \"name\" LIMIT " + TrackConstants.ANALYSIS_TOP_LIMIT;
		return jdbc.queryForList(sql, args.toArray());
	}

	// ==================== 趋势 ====================

	/**
	 * 时间序列：days&lt;=2 走 track_stats_5m（5 分钟粒度，开放窗口已由任务重算覆盖当日）；
	 * days&gt;2 走 track_stats_day（日粒度）。dimType=overview 时——day 表取 dim_key=ALL 行，
	 * 5m 表无 overview 维，等价取 event 维全量求和（全站口径；sessionCount 为多事件名求和仅供参考）。
 * day 表只含闭合日：overview 维的当日行由明细直算追加（与 overview 卡片今日部分同口径）。
	 */
	public List<Map<String, Object>> trend(String appKey, Integer days, String dimType, String dimKey) {
		appKey = assertAppKey(appKey);
		int d = assertDays(days);
		String dim = assertDimType(dimType);
		String tenant = currentTenant();
		return self.getObject().cachedTrend(appKey, d, dim, dimKey, tenant, timezones.zoneFor(tenant).getId());
	}

	@Cached(name = "track:ana:trend:",
		key = "#appKey + ':' + #days + ':' + #dimType + ':' + #dimKey + ':' + #tenant + ':' + #zoneId",
		expire = TrackConstants.ANALYSIS_CACHE_SECONDS, cacheType = CacheType.LOCAL)
	public List<Map<String, Object>> cachedTrend(String appKey, int days, String dimType, String dimKey, String tenant,
												 String zoneId) {
		// 按租户时区切日：「今天」从当地 0 点开始（UX-012 后续）
		TrackDayWindow w = TrackDayWindow.of(ZoneId.of(zoneId), Instant.now(), days);
		LocalDate today = w.today();
		LocalDate from = w.fromDate();
		if (days <= 2) {
			String effectiveDim = TrackConstants.DIM_OVERVIEW.equals(dimType) ? TrackConstants.DIM_EVENT : dimType;
			List<Object> args = new ArrayList<>(List.of(appKey, w.fromUtcWall(), effectiveDim));
			StringBuilder sql = new StringBuilder(
				"SELECT CAST(EXTRACT(EPOCH FROM bucket_time) * 1000 AS BIGINT) AS \"time\","
					+ " coalesce(sum(pv), 0) AS \"pv\", coalesce(sum(event_count), 0) AS \"eventCount\","
					+ " coalesce(sum(session_count), 0) AS \"sessionCount\""
					+ " FROM track_stats_5m WHERE app_key = ? AND bucket_time >= ? AND dim_type = ?");
			if (dimKey != null && !dimKey.isBlank() && !TrackConstants.DIM_OVERVIEW.equals(dimType)) {
				sql.append(" AND dim_key = ?");
				args.add(dimKey);
			}
			sql.append(tenantFrag(null, tenant, args));
			sql.append(" GROUP BY bucket_time ORDER BY bucket_time");
			return jdbc.queryForList(sql.toString(), args.toArray());
		}
		if (!w.isUtc()) {
			return fillMissingDays(localDailyRows(appKey, w, dimType, dimKey, tenant), from, today);
		}
		List<Object> args = new ArrayList<>(List.of(appKey, from, today, dimType));
		StringBuilder sql = new StringBuilder(
			"SELECT to_char(stat_date, 'YYYY-MM-DD') AS \"date\","
				+ " coalesce(sum(pv), 0) AS \"pv\", coalesce(sum(uv), 0) AS \"uv\","
				+ " coalesce(sum(session_count), 0) AS \"sessionCount\","
				+ " coalesce(sum(bounce_count), 0) AS \"bounceCount\", coalesce(sum(event_count), 0) AS \"eventCount\""
				+ " FROM track_stats_day WHERE app_key = ? AND stat_date >= ? AND stat_date <= ? AND dim_type = ?");
		if (TrackConstants.DIM_OVERVIEW.equals(dimType)) {
			sql.append(" AND dim_key = '" + TrackConstants.DIM_KEY_ALL + "'");
		} else if (dimKey != null && !dimKey.isBlank()) {
			sql.append(" AND dim_key = ?");
			args.add(dimKey);
		}
		sql.append(tenantFrag(null, tenant, args));
		sql.append(" GROUP BY stat_date ORDER BY stat_date");
		List<Map<String, Object>> rows = new ArrayList<>(jdbc.queryForList(sql.toString(), args.toArray()));
		// stats_day 只写闭合日（TrackStatsDayJob 不落当日），当日行须直算明细补上，否则当日数据在趋势里永远缺席
		if (TrackConstants.DIM_OVERVIEW.equals(dimType)) {
			String todayKey = today.toString();
			rows.removeIf(r -> todayKey.equals(String.valueOf(r.get("date"))));
			Map<String, Object> todayRow = todayOverviewRow(appKey, today, tenant);
			if (num(todayRow, "eventCount") > 0) {
				rows.add(todayRow);
			}
		}
		return fillMissingDays(rows, from, today);
	}

	/** 日粒度补零：区间内每天一行（from..today 升序），无数据日各指标为 0，图表横轴恒为 days 个点（UX-011） */
	static List<Map<String, Object>> fillMissingDays(List<Map<String, Object>> rows, LocalDate from, LocalDate to) {
		Map<String, Map<String, Object>> byDate = new HashMap<>();
		for (Map<String, Object> r : rows) {
			byDate.put(String.valueOf(r.get("date")), r);
		}
		List<Map<String, Object>> out = new ArrayList<>();
		for (LocalDate d = from; !d.isAfter(to); d = d.plusDays(1)) {
			Map<String, Object> r = byDate.get(d.toString());
			if (r == null) {
				r = new LinkedHashMap<>();
				r.put("date", d.toString());
				r.put("pv", 0L);
				r.put("uv", 0L);
				r.put("sessionCount", 0L);
				r.put("bounceCount", 0L);
				r.put("eventCount", 0L);
			}
			out.add(r);
		}
		return out;
	}

	/**
	 * 非 UTC 时区的日粒度：track_stats_day 按 UTC 日汇总无法改切，改为明细按当地日期直算（窗口内分区裁剪）。
	 * 口径同当日直算行：sessionCount=活跃会话，bounceCount=当地当天开始的已结算会话里 pageviews<=1 的数量（仅总览）。
	 */
	private List<Map<String, Object>> localDailyRows(String appKey, TrackDayWindow w, String dimType, String dimKey,
													 String tenant) {
		String zone = w.zone().getId();
		List<Object> args = new ArrayList<>(List.of(zone, appKey, w.fromOffset()));
		StringBuilder sql = new StringBuilder(
			"SELECT to_char((e.received_at AT TIME ZONE ?)::date, 'YYYY-MM-DD') AS \"date\","
				+ " count(*) FILTER (WHERE e.event_name = '" + TrackConstants.EVENT_PAGEVIEW + "') AS \"pv\","
				+ " " + TrackConstants.UV_EXPR + " AS \"uv\","
				+ " count(DISTINCT e.session_id) AS \"sessionCount\", count(*) AS \"eventCount\""
				+ " FROM track_event e WHERE e.app_key = ? AND e.received_at >= ?");
		String dimExpr = switch (dimType) {
			case TrackConstants.DIM_EVENT -> "e.event_name";
			case TrackConstants.DIM_PAGE -> "left(coalesce(nullif(e.route_path, ''), e.url_path), " + TrackConstants.DIM_MAX_LEN + ")";
			case TrackConstants.DIM_REFERRER -> "e.referrer_domain";
			case TrackConstants.DIM_DEVICE -> "e.device";
			default -> null;
		};
		if (dimExpr != null) {
			if (dimKey != null && !dimKey.isBlank()) {
				sql.append(" AND ").append(dimExpr).append(" = ?");
				args.add(dimKey);
			} else {
				sql.append(" AND ").append(dimExpr).append(" IS NOT NULL AND ").append(dimExpr).append(" <> ''");
			}
		}
		sql.append(tenantFrag("e", tenant, args)).append(" GROUP BY 1 ORDER BY 1");
		List<Map<String, Object>> rows = new ArrayList<>();
		for (Map<String, Object> r : jdbc.queryForList(sql.toString(), args.toArray())) {
			Map<String, Object> row = new LinkedHashMap<>(r);
			row.put("bounceCount", 0L);
			rows.add(row);
		}
		if (TrackConstants.DIM_OVERVIEW.equals(dimType)) {
			List<Object> sArgs = new ArrayList<>(List.of(zone, appKey, w.fromUtcWall()));
			String sessSql = "SELECT to_char(((start_time AT TIME ZONE 'UTC') AT TIME ZONE ?)::date, 'YYYY-MM-DD') AS d,"
				+ " count(*) FILTER (WHERE pageviews <= 1) AS bounce FROM track_session"
				+ " WHERE app_key = ? AND start_time >= ? AND settled = 1 AND is_deleted = 0"
				+ tenantFrag(null, tenant, sArgs) + " GROUP BY 1";
			Map<String, Long> bounce = new HashMap<>();
			for (Map<String, Object> r : jdbc.queryForList(sessSql, sArgs.toArray())) {
				bounce.put(String.valueOf(r.get("d")), num(r, "bounce"));
			}
			for (Map<String, Object> row : rows) {
				row.put("bounceCount", bounce.getOrDefault(String.valueOf(row.get("date")), 0L));
			}
		}
		return rows;
	}

	/** 当日总览行（明细直算，口径与 overview 今日部分一致；bounceCount 取当日已结算会话） */
	private Map<String, Object> todayOverviewRow(String appKey, LocalDate today, String tenant) {
		OffsetDateTime todayStart = today.atStartOfDay().atOffset(ZoneOffset.UTC);
		List<Object> args = new ArrayList<>(List.of(appKey, todayStart));
		Map<String, Object> ev = queryOne(
			"SELECT count(*) FILTER (WHERE e.event_name = '" + TrackConstants.EVENT_PAGEVIEW + "') AS pv,"
				+ " count(*) AS ec, count(DISTINCT e.session_id) AS sc,"
				+ " " + TrackConstants.UV_EXPR + " AS uv"
				+ " FROM track_event e"
				+ " WHERE e.app_key = ? AND e.received_at >= ?" + tenantFrag("e", tenant, args),
			args);
		List<Object> sessArgs = new ArrayList<>(List.of(appKey, today.atStartOfDay()));
		Map<String, Object> sess = queryOne(
			"SELECT count(*) FILTER (WHERE pageviews <= 1) AS bounce FROM track_session"
				+ " WHERE app_key = ? AND start_time >= ? AND settled = 1 AND is_deleted = 0"
				+ tenantFrag(null, tenant, sessArgs),
			sessArgs);
		Map<String, Object> row = new LinkedHashMap<>();
		row.put("date", today.toString());
		row.put("pv", num(ev, "pv"));
		row.put("uv", num(ev, "uv"));
		row.put("sessionCount", num(ev, "sc"));
		row.put("bounceCount", num(sess, "bounce"));
		row.put("eventCount", num(ev, "ec"));
		return row;
	}

	// ==================== 页面 / 性能 ====================

	/** Top 页面（PV/UV/平均停留）：明细直算（分区裁剪）；UV 口径同 UV_EXPR；平均停留 = $pageleave.duration_ms 均值 */
	public List<Map<String, Object>> pages(String appKey, Integer days, Integer limit) {
		appKey = assertAppKey(appKey);
		int d = assertDays(days);
		int top = clampLimit(limit, TrackConstants.ANALYSIS_TOP_LIMIT);
		String tenant = currentTenant();
		return self.getObject().cachedPages(appKey, d, top, tenant, timezones.zoneFor(tenant).getId());
	}

	@Cached(name = "track:ana:pages:", key = "#appKey + ':' + #days + ':' + #limit + ':' + #tenant + ':' + #zoneId",
		expire = TrackConstants.ANALYSIS_CACHE_SECONDS, cacheType = CacheType.LOCAL)
	public List<Map<String, Object>> cachedPages(String appKey, int days, int limit, String tenant, String zoneId) {
		OffsetDateTime from = TrackDayWindow.of(ZoneId.of(zoneId), Instant.now(), days).fromOffset();
		List<Object> args = new ArrayList<>(List.of(appKey, from));
		String sql = "SELECT left(coalesce(nullif(e.route_path, ''), e.url_path), " + TrackConstants.DIM_MAX_LEN + ") AS \"pagePath\","
			+ " count(*) FILTER (WHERE e.event_name = '" + TrackConstants.EVENT_PAGEVIEW + "') AS \"pv\","
			+ " " + TrackConstants.UV_EXPR + " AS \"uv\","
			+ " coalesce(round(avg(e.duration_ms) FILTER (WHERE e.event_name = '$pageleave'"
			+ " AND e.duration_ms IS NOT NULL)), 0)::bigint AS \"avgDurationMs\""
			+ " FROM track_event e"
			+ " WHERE e.app_key = ? AND e.received_at >= ?"
			+ " AND coalesce(nullif(e.route_path, ''), e.url_path) IS NOT NULL"
			+ tenantFrag("e", tenant, args)
			+ " GROUP BY 1 ORDER BY \"pv\" DESC, \"pagePath\" LIMIT " + limit;
		return jdbc.queryForList(sql, args.toArray());
	}

	/** Web Vitals 分位（直方图插值，非实时 percentile）：每 metric 返回 count/avg/p50/p75/p95 */
	public List<Map<String, Object>> vitals(String appKey, Integer days, String routePath) {
		appKey = assertAppKey(appKey);
		int d = assertDays(days);
		String tenant = currentTenant();
		return self.getObject().cachedVitals(appKey, d, routePath, tenant, timezones.zoneFor(tenant).getId());
	}

	@Cached(name = "track:ana:vitals:", key = "#appKey + ':' + #days + ':' + #routePath + ':' + #tenant + ':' + #zoneId",
		expire = TrackConstants.ANALYSIS_CACHE_SECONDS, cacheType = CacheType.LOCAL)
	public List<Map<String, Object>> cachedVitals(String appKey, int days, String routePath, String tenant, String zoneId) {
		TrackDayWindow w = TrackDayWindow.of(ZoneId.of(zoneId), Instant.now(), days);
		List<Object> args;
		StringBuilder sql;
		boolean byRoute = routePath != null && !routePath.isBlank();
		if (w.isUtc()) {
			args = new ArrayList<>(List.of(appKey, w.fromDate(), w.today()));
			sql = new StringBuilder(
				"SELECT metric, bucket, sum(cnt) AS c FROM track_stats_vitals"
					+ " WHERE app_key = ? AND stat_date >= ? AND stat_date <= ?");
			if (byRoute) {
				sql.append(" AND url_path = ?");
				args.add(routePath);
			}
		} else {
			// 非 UTC：track_stats_vitals 按 UTC 日汇总，改为窗口内 $web_vitals 明细直接分桶（桶界与 TrackVitalsJob 同一表达式）
			args = new ArrayList<>(List.of(appKey, w.fromOffset()));
			sql = new StringBuilder(
				"SELECT props->>'" + TrackConstants.PROP_VITALS_METRIC + "' AS metric, "
					+ TrackVitalsJob.bucketCaseSql() + " AS bucket, count(*) AS c FROM track_event"
					+ " WHERE app_key = ? AND event_name = '$web_vitals' AND received_at >= ?"
					+ " AND props->>'" + TrackConstants.PROP_VITALS_METRIC + "' IN ('lcp', 'inp', 'cls', 'fcp', 'ttfb')"
					+ " AND props->>'" + TrackConstants.PROP_VITALS_VALUE + "' ~ '^[0-9]+(\\.[0-9]+)?$'");
			if (byRoute) {
				sql.append(" AND coalesce(nullif(route_path, ''), url_path, '" + TrackConstants.VITALS_DIM_UNKNOWN + "') = ?");
				args.add(routePath);
			}
		}
		sql.append(tenantFrag(null, tenant, args));
		sql.append(" GROUP BY metric, bucket ORDER BY metric, bucket");
		List<Map<String, Object>> rows = jdbc.queryForList(sql.toString(), args.toArray());

		// 按 metric 聚桶 → 插值分位 + 桶中值估算均值
		Map<String, long[]> histograms = new LinkedHashMap<>();
		for (Map<String, Object> row : rows) {
			String metric = String.valueOf(row.get("metric"));
			long[] bounds = boundsOf(metric);
			long[] counts = histograms.computeIfAbsent(metric, k -> new long[bounds.length + 1]);
			int bucket = ((Number) row.get("bucket")).intValue();
			if (bucket >= 0 && bucket < counts.length) {
				counts[bucket] += ((Number) row.get("c")).longValue();
			}
		}
		List<Map<String, Object>> result = new ArrayList<>();
		for (Map.Entry<String, long[]> entry : histograms.entrySet()) {
			String metric = entry.getKey();
			long[] bounds = boundsOf(metric);
			long[] counts = entry.getValue();
			long total = 0;
			for (long c : counts) {
				total += c;
			}
			Map<String, Object> item = new LinkedHashMap<>();
			item.put("metric", metric);
			item.put("count", total);
			item.put("avg", Math.round(meanEstimate(bounds, counts)));
			item.put("p50", Math.round(interpolate(bounds, counts, 0.50)));
			item.put("p75", Math.round(interpolate(bounds, counts, 0.75)));
			item.put("p95", Math.round(interpolate(bounds, counts, 0.95)));
			result.add(item);
		}
		return result;
	}

	/** 直方图线性插值分位：目标桶内按计数占比内插；溢出桶返回下界（package-private 供测试核对口径） */
	static double interpolate(long[] bounds, long[] counts, double q) {
		long total = 0;
		for (long c : counts) {
			total += c;
		}
		if (total == 0) {
			return 0.0;
		}
		double target = q * total;
		long cum = 0;
		for (int i = 0; i < counts.length; i++) {
			long c = counts[i];
			if (c == 0) {
				continue;
			}
			if (cum + c >= target) {
				double lower = i == 0 ? 0.0 : bounds[i - 1];
				double upper = i >= bounds.length ? bounds[bounds.length - 1] : bounds[i];
				if (upper <= lower) {
					return lower;
				}
				double frac = (target - cum) / (double) c;
				return lower + (upper - lower) * Math.min(1.0, Math.max(0.0, frac));
			}
			cum += c;
		}
		return bounds[bounds.length - 1];
	}

	/** 均值估算：桶中值加权（溢出桶取下界；直方图固有加权误差，口径注明） */
	static double meanEstimate(long[] bounds, long[] counts) {
		double sum = 0;
		long total = 0;
		for (int i = 0; i < counts.length; i++) {
			if (counts[i] == 0) {
				continue;
			}
			double lower = i == 0 ? 0.0 : bounds[i - 1];
			double upper = i >= bounds.length ? bounds[bounds.length - 1] : bounds[i];
			double mid = upper <= lower ? lower : (lower + upper) / 2.0;
			sum += mid * counts[i];
			total += counts[i];
		}
		return total == 0 ? 0.0 : sum / total;
	}

	/** metric → 桶界（CLS 千分制，其余毫秒对数桶；未知 metric 按毫秒桶——采集白名单已约束值域） */
	private static long[] boundsOf(String metric) {
		return TrackConstants.VITALS_METRIC_CLS.equals(metric)
			? TrackConstants.VITALS_CLS_BUCKET_BOUNDS : TrackConstants.VITALS_MS_BUCKET_BOUNDS;
	}

	// ==================== 事件分析 ====================

	/** 事件分析分页：按事件名分组（次数/会话/UV/最近发生）；UV 口径见 {@link TrackConstants#UV_EXPR} */
	public Page<Map<String, Object>> eventsPage(String appKey, String eventName, Integer days, long pageNum, long pageSize) {
		appKey = assertAppKey(appKey);
		String tenant = currentTenant();
		int d = assertDays(days);
		long size = clampPageSize(pageSize);
		// 按租户时区切日：「今天」从当地 0 点开始（与概览/错误列表同口径）
		OffsetDateTime from = TrackDayWindow.of(timezones.zoneFor(tenant), Instant.now(), d).fromOffset();
		StringBuilder cond = new StringBuilder(" FROM track_event e WHERE e.app_key = ? AND e.received_at >= ?");
		List<Object> args = new ArrayList<>(List.of(appKey, from));
		if (eventName != null && !eventName.isBlank()) {
			cond.append(" AND e.event_name = ?");
			args.add(eventName);
		}
		cond.append(tenantFrag("e", tenant, args));

		Long total = jdbc.queryForObject("SELECT count(DISTINCT e.event_name)" + cond, Long.class, args.toArray());
		List<Object> pageArgs = new ArrayList<>(args);
		String rowsSql = "SELECT e.event_name AS \"eventName\", count(*) AS \"eventCount\","
			+ " count(DISTINCT e.session_id) AS \"sessionCount\","
			+ " " + TrackConstants.UV_EXPR + " AS \"uv\","
			+ " CAST(EXTRACT(EPOCH FROM max(e.received_at)) * 1000 AS BIGINT) AS \"lastTime\""
			+ cond + " GROUP BY e.event_name ORDER BY \"eventCount\" DESC, \"eventName\" LIMIT ? OFFSET ?";
		pageArgs.add(size);
		pageArgs.add((pageNum - 1) * size);
		List<Map<String, Object>> records = jdbc.queryForList(rowsSql, pageArgs.toArray());
		return new Page<>(records, pageNum, size, total == null ? 0L : total);
	}

	/** 实时事件流：Redis Stream XREVRANGE 尾部（不等落库，§5.2 实时通道）；先校验本租户应用可见性 */
	public List<Map<String, Object>> eventsRealtime(String appKey, Integer limit) {
		appKey = assertAppKey(appKey);
		String tenant = currentTenant();
		assertAppVisible(appKey, tenant);
		int top = clampLimit(limit, 100);
		List<MapRecord<String, Object, Object>> records;
		try {
			records = redis.opsForStream().reverseRange(TrackConstants.STREAM_KEY_PREFIX + appKey,
				Range.unbounded(), Limit.limit().count(top));
		} catch (RuntimeException e) {
			log.warn("实时流读取 Redis 不可用：{}", e.getMessage());
			return List.of();
		}
		List<Map<String, Object>> result = new ArrayList<>();
		if (records == null) {
			return result;
		}
		for (MapRecord<String, Object, Object> record : records) {
			Map<Object, Object> value = record.getValue();
			Map<String, Object> item = new LinkedHashMap<>();
			item.put("id", record.getId() == null ? null : record.getId().getValue());
			item.put("eventId", textOf(value.get(TrackConstants.FIELD_EVENT_ID)));
			item.put("eventName", textOf(value.get(TrackConstants.FIELD_EVENT_NAME)));
			String ts = textOf(value.get(TrackConstants.FIELD_TS));
			item.put("ts", ts == null ? null : Long.parseLong(ts));
			item.put("distinctId", textOf(value.get(TrackConstants.FIELD_DISTINCT_ID)));
			item.put("sessionId", textOf(value.get(TrackConstants.FIELD_SESSION_ID)));
			item.put("userId", textOf(value.get(TrackConstants.FIELD_USER_ID)));
			item.put("urlPath", textOf(value.get(TrackConstants.PROP_URL_PATH)));
			result.add(item);
		}
		return result;
	}

	/** 当前在线人数：ZSET 5 分钟窗 ZCOUNT（§5.2）；先校验本租户应用可见性 */
	public Map<String, Object> online(String appKey) {
		appKey = assertAppKey(appKey);
		String tenant = currentTenant();
		assertAppVisible(appKey, tenant);
		long now = System.currentTimeMillis();
		Long count;
		try {
			count = redis.opsForZSet().count(TrackConstants.ONLINE_KEY_PREFIX + appKey,
				now - TrackConstants.ONLINE_WINDOW_MS, now);
		} catch (RuntimeException e) {
			log.warn("在线人数读取 Redis 不可用：{}", e.getMessage());
			count = 0L;
		}
		Map<String, Object> result = new LinkedHashMap<>();
		result.put("online", count == null ? 0L : count);
		result.put("windowSeconds", TrackConstants.ONLINE_WINDOW_MS / 1000L);
		return result;
	}

	// ==================== 错误监控 ====================

	/** 错误分组分页：按 error_fingerprint 聚合（次数/影响会话/首末次/最近消息：按发生时间 ts 取最晚一条） */
	public Page<Map<String, Object>> errorsPage(String appKey, Integer days, long pageNum, long pageSize) {
		appKey = assertAppKey(appKey);
		String tenant = currentTenant();
		int d = assertDays(days);
		long size = clampPageSize(pageSize);
		// 按租户时区切日：「今天」从当地 0 点开始
		OffsetDateTime from = TrackDayWindow.of(timezones.zoneFor(tenant), Instant.now(), d).fromOffset();
		StringBuilder cond = new StringBuilder(" FROM track_event WHERE app_key = ? AND event_name = '"
			+ TrackConstants.EVENT_ERROR + "' AND error_fingerprint IS NOT NULL AND received_at >= ?");
		List<Object> args = new ArrayList<>(List.of(appKey, from));
		cond.append(tenantFrag(null, tenant, args));

		Long total = jdbc.queryForObject("SELECT count(DISTINCT error_fingerprint)" + cond, Long.class, args.toArray());
		List<Object> pageArgs = new ArrayList<>(args);
		String rowsSql = "SELECT error_fingerprint AS \"fingerprint\", count(*) AS \"eventCount\","
			+ " count(DISTINCT session_id) AS \"sessionCount\","
			+ " CAST(EXTRACT(EPOCH FROM min(received_at)) * 1000 AS BIGINT) AS \"firstTime\","
			+ " CAST(EXTRACT(EPOCH FROM max(received_at)) * 1000 AS BIGINT) AS \"lastTime\","
			// 代表消息取发生时间（ts，校时后）最晚的一条，同 ts 以 id 定序——与错误明细/时间线排序口径一致（非接收时间）
			+ " (array_agg(props->>'" + TrackConstants.PROP_ERROR_MESSAGE + "' ORDER BY ts DESC, id DESC))[1] AS \"message\""
			+ cond + " GROUP BY error_fingerprint ORDER BY \"lastTime\" DESC LIMIT ? OFFSET ?";
		pageArgs.add(size);
		pageArgs.add((pageNum - 1) * size);
		List<Map<String, Object>> records = jdbc.queryForList(rowsSql, pageArgs.toArray());
		return new Page<>(records, pageNum, size, total == null ? 0L : total);
	}

	/** 错误组内明细：原始堆栈/release/面包屑 props（props 原样 JSON 文本，前端自解析渲染） */
	public Page<Map<String, Object>> errorDetail(String appKey, String fingerprint, Integer days, long pageNum, long pageSize) {
		appKey = assertAppKey(appKey);
		if (fingerprint == null || fingerprint.isBlank()) {
			throw new ServiceException("请先从错误列表里打开一组错误。明细是按这组错误分开的");
		}
		String tenant = currentTenant();
		int d = assertDays(days);
		long size = clampPageSize(pageSize);
		// 按租户时区切日：「今天」从当地 0 点开始
		OffsetDateTime from = TrackDayWindow.of(timezones.zoneFor(tenant), Instant.now(), d).fromOffset();
		StringBuilder cond = new StringBuilder(" FROM track_event WHERE app_key = ? AND event_name = '"
			+ TrackConstants.EVENT_ERROR + "' AND error_fingerprint = ? AND received_at >= ?");
		List<Object> args = new ArrayList<>(List.of(appKey, fingerprint, from));
		cond.append(tenantFrag(null, tenant, args));

		Long total = jdbc.queryForObject("SELECT count(*)" + cond, Long.class, args.toArray());
		List<Object> pageArgs = new ArrayList<>(args);
		String rowsSql = "SELECT event_id AS \"eventId\", session_id AS \"sessionId\", distinct_id AS \"distinctId\","
			+ " url_path AS \"urlPath\", route_path AS \"routePath\","
			+ " CAST(EXTRACT(EPOCH FROM ts) * 1000 AS BIGINT) AS \"time\","
			+ " props->>'" + TrackConstants.PROP_ERROR_MESSAGE + "' AS \"message\","
			+ " props->>'release' AS \"release\", props->>'" + TrackConstants.PROP_ERROR_STACK + "' AS \"stack\","
			+ " props::text AS \"props\""
			// 按显示的发生时间（ts，校时后）倒序；同 ts 用 id 定序，保证翻页稳定不重不漏
			+ cond + " ORDER BY ts DESC, id DESC LIMIT ? OFFSET ?";
		pageArgs.add(size);
		pageArgs.add((pageNum - 1) * size);
		List<Map<String, Object>> 		records = jdbc.queryForList(rowsSql, pageArgs.toArray());
		return new Page<>(records, pageNum, size, total == null ? 0L : total);
	}

	// ==================== 地理（G106） ====================

	/**
	 * 地域分布 + 精确热力点。属地按 IP 省份归一（明细限窗，不进 rollup）；
	 * 坐标点取最近 {@link TrackConstants#GEO_POINTS_MAX} 条有列事件。
	 */
	public Map<String, Object> geo(String appKey, Integer days) {
		appKey = assertAppKey(appKey);
		String tenant = currentTenant();
		assertAppVisible(appKey, tenant);
		int d = assertDays(days);
		return queryGeo(appKey, d, tenant);
	}

	/** 即席查询（无缓存）：摄入后立刻可在概览/工作台看见，便于验收与运营排障。 */
	public Map<String, Object> queryGeo(String appKey, int days, String tenant) {
		// 按租户时区切日：「今天」从当地 0 点开始（与概览/错误列表同口径）
		OffsetDateTime from = TrackDayWindow.of(timezones.zoneFor(tenant), Instant.now(), days).fromOffset();
		String regionExpr = "CASE"
			+ " WHEN e.ip_region IS NULL OR btrim(e.ip_region) = '' THEN '" + TrackConstants.GEO_REGION_UNKNOWN + "'"
			+ " WHEN e.ip_region LIKE '%内网%' THEN '" + TrackConstants.GEO_REGION_INTRANET + "'"
			+ " WHEN split_part(e.ip_region, '|', 3) NOT IN ('', '0') THEN split_part(e.ip_region, '|', 3)"
			+ " WHEN split_part(e.ip_region, '|', 4) NOT IN ('', '0') THEN split_part(e.ip_region, '|', 4)"
			+ " WHEN split_part(e.ip_region, '|', 1) NOT IN ('', '0') THEN split_part(e.ip_region, '|', 1)"
			+ " ELSE '" + TrackConstants.GEO_REGION_UNKNOWN + "' END";

		List<Object> regionArgs = new ArrayList<>(List.of(appKey, from));
		List<Map<String, Object>> regions = jdbc.queryForList(
			"SELECT " + regionExpr + " AS \"region\","
				+ " count(*) FILTER (WHERE e.event_name = '" + TrackConstants.EVENT_PAGEVIEW + "') AS pv,"
				+ " count(*) AS \"eventCount\","
				+ " " + TrackConstants.UV_EXPR + " AS uv"
				+ " FROM track_event e"
				+ " WHERE e.app_key = ? AND e.received_at >= ?" + tenantFrag("e", tenant, regionArgs)
				+ " GROUP BY 1 ORDER BY \"eventCount\" DESC, \"region\" LIMIT " + TrackConstants.GEO_REGIONS_MAX,
			regionArgs.toArray());

		List<Object> pointArgs = new ArrayList<>(List.of(appKey, from));
		List<Map<String, Object>> points = jdbc.queryForList(
			"SELECT e.geo_lon AS lon, e.geo_lat AS lat, e.event_name AS \"eventName\","
				+ " CAST(EXTRACT(EPOCH FROM e.ts) * 1000 AS BIGINT) AS ts,"
				+ " e.url_path AS \"urlPath\""
				+ " FROM track_event e WHERE e.app_key = ? AND e.received_at >= ?"
				+ " AND e.geo_lon IS NOT NULL AND e.geo_lat IS NOT NULL"
				+ tenantFrag("e", tenant, pointArgs)
				+ " ORDER BY e.received_at DESC LIMIT " + TrackConstants.GEO_POINTS_MAX,
			pointArgs.toArray());

		List<Object> countArgs = new ArrayList<>(List.of(appKey, from));
		Long geoCount = jdbc.queryForObject(
			"SELECT count(*) FROM track_event e WHERE e.app_key = ? AND e.received_at >= ?"
				+ " AND e.geo_lon IS NOT NULL" + tenantFrag("e", tenant, countArgs),
			Long.class, countArgs.toArray());

		Map<String, Object> result = new LinkedHashMap<>();
		result.put("regions", regions);
		result.put("points", points);
		result.put("geoCount", geoCount == null ? 0L : geoCount);
		return result;
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

	/** 应用可见性（Redis 通道无 SQL 租户条件，先显式校验本租户归属；0 行 = 不存在或他租户） */
	private void assertAppVisible(String appKey, String tenant) {
		List<Object> args = new ArrayList<>(List.of(appKey));
		String sql = "SELECT count(*) FROM track_app WHERE app_key = ? AND is_deleted = 0" + tenantFrag(null, tenant, args);
		Long count = jdbc.queryForObject(sql, Long.class, args.toArray());
		if (count == null || count == 0) {
			throw new ServiceException("应用不存在或无权访问。请刷新应用列表后再看；如果这是别的租户的应用，请用那个租户的账号打开");
		}
	}

	private String assertAppKey(String appKey) {
		if (appKey == null || appKey.isBlank()) {
			throw new ServiceException("请先选择应用。分析是按应用分开的");
		}
		String key = appKey.trim();
		if (key.length() > TrackConstants.APP_KEY_MAX_LEN) {
			throw new ServiceException("应用标识不对。请从应用列表里重新选择");
		}
		// 原始 SQL 不看租户：完全没有这个应用才说明「不存在」。
		// 别的租户有同名应用时仍返回标识，统计查询按租户滤成空结果；实时通道另做本租户校验。
		Long found = jdbc.queryForObject(
			"SELECT count(*) FROM track_app WHERE app_key = ? AND is_deleted = 0", Long.class, key);
		if (found == null || found == 0) {
			throw new ServiceException("这个应用不存在。请刷新应用列表后再看");
		}
		return key;
	}

	/** dimType 白名单（非法值 400，防拼接注入与脏维度） */
	private String assertDimType(String dimType) {
		String dim = dimType == null || dimType.isBlank() ? TrackConstants.DIM_OVERVIEW : dimType;
		if (!TrackConstants.DIM_OVERVIEW.equals(dim) && !TrackConstants.DIM_EVENT.equals(dim)
			&& !TrackConstants.DIM_PAGE.equals(dim) && !TrackConstants.DIM_REFERRER.equals(dim)
			&& !TrackConstants.DIM_DEVICE.equals(dim)) {
			throw new ServiceException("分析维度不对。请从总览、事件、页面、来源或设备里重新选择");
		}
		return dim;
	}

	/** 统计天数：缺省 7 天；须在 1..{@value TrackConstants#ANALYSIS_DAYS_MAX}，否则说明改回这个范围 */
	private int assertDays(Integer days) {
		if (days == null) {
			return 7;
		}
		if (days < 1 || days > TrackConstants.ANALYSIS_DAYS_MAX) {
			throw new ServiceException("统计天数只支持 1 到 " + TrackConstants.ANALYSIS_DAYS_MAX
				+ " 天。请改到这个范围里再查");
		}
		return days;
	}

	private long clampPageSize(long pageSize) {
		if (pageSize < 1) {
			return 10;
		}
		return Math.min(pageSize, TrackConstants.QUERY_PAGE_SIZE_MAX);
	}

	private int clampLimit(Integer limit, int defaultLimit) {
		if (limit == null || limit < 1) {
			return defaultLimit;
		}
		return Math.min(limit, TrackConstants.QUERY_PAGE_SIZE_MAX);
	}

	private Map<String, Object> queryOne(String sql, List<Object> args) {
		List<Map<String, Object>> rows = jdbc.queryForList(sql, args.toArray());
		return rows.isEmpty() ? Map.of() : rows.get(0);
	}

	private long num(Map<String, Object> row, String key) {
		Object value = row.get(key);
		return value == null ? 0L : ((Number) value).longValue();
	}

	private String textOf(Object value) {
		return value == null ? null : String.valueOf(value);
	}
}
