package com.mugsun.boot.track;

import cn.dev33.satoken.stp.StpUtil;
import com.mugsun.boot.common.constant.TrackConstants;
import com.mugsun.boot.tenant.TenantContext;
import com.mugsun.core.tool.exception.ServiceException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import javax.sql.DataSource;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * 空间行为分析（P0）：{@code GET /system/track/spatial}。
 * <p>聚合用 {@code round(geo_lon/geo_lat, p) GROUP BY}，不依赖 track PostGIS；
 * 不回 IP / geo_acc / 3857。限流 Redis ZSET 滑窗，键前缀
 * {@link TrackConstants#SPATIAL_RATE_LIMIT_KEY_PREFIX}（与 collect {@code rl:} 隔离）。
 * <p>漏斗：本刀无漏斗定义表；带 funnelId/funnelSteps 时按「无漏斗数据」处理——
 * {@code converted} 全 false；{@code convertedOnly=true} 则空态。
 */
@Service
@TrackDS
public class TrackSpatialService {

	private static final Logger log = LoggerFactory.getLogger(TrackSpatialService.class);

	private static final String MODE_AGG = "agg";
	private static final String MODE_POINTS = "points";

	private final JdbcTemplate jdbc;
	private final StringRedisTemplate redis;
	private final TrackTimezoneProperties timezones;

	public TrackSpatialService(DataSource dataSource, StringRedisTemplate redis,
							   TrackTimezoneProperties timezones) {
		this.jdbc = new JdbcTemplate(dataSource);
		this.redis = redis;
		this.timezones = timezones;
	}

	/**
	 * 空间查询入口。
	 *
	 * @param appKey         必填
	 * @param days           1..30，默认 7
	 * @param mode           agg|points，默认 agg
	 * @param bbox           可选 minLon,minLat,maxLon,maxLat（不支持跨 180°）
	 * @param zoom           可选 1..20
	 * @param gridPrecision  可选 1..4，优先于 zoom
	 * @param eventNames     可选逗号分隔事件名
	 * @param funnelId       可选；本刀无定义表时忽略转化
	 * @param funnelSteps    可选逗号分隔步骤下标；同上
	 * @param windowHours    可选，白名单同漏斗（本刀无定义时仅校验）
	 * @param convertedOnly  默认 false
	 */
	public Map<String, Object> spatial(String appKey, Integer days, String mode, String bbox,
									   Integer zoom, Integer gridPrecision, String eventNames,
									   Long funnelId, String funnelSteps, Long windowHours,
									   Boolean convertedOnly) {
		assertRateLimit();
		appKey = assertAppKey(appKey);
		String tenant = currentTenant();
		assertAppVisible(appKey, tenant);
		int d = assertDays(days);
		String m = assertMode(mode);
		int precision = resolvePrecision(zoom, gridPrecision);
		double[] box = parseBbox(bbox);
		List<String> names = parseEventNames(eventNames);
		parseFunnelSteps(funnelSteps); // 校验形态；本刀无定义表
		assertWindowOptional(windowHours);
		boolean onlyConverted = Boolean.TRUE.equals(convertedOnly);
		// 本刀无漏斗定义表：funnelId/funnelSteps 已在上方校验形态；converted 恒 false
		// （funnelId 保留入参以对齐契约，待定义表落地后再解析）
		if (funnelId != null) {
			log.trace("spatial funnelId={} ignored until funnel-def table exists", funnelId);
		}
		if (onlyConverted) {
			return emptyResult(m);
		}
		OffsetDateTime from = TrackDayWindow.of(timezones.zoneFor(tenant), Instant.now(), d).fromOffset();
		if (MODE_POINTS.equals(m)) {
			return queryPoints(appKey, tenant, from, box, names);
		}
		return queryAgg(appKey, tenant, from, box, names, precision);
	}

	/** zoom→p：≤5→1；6～8→2；9～11→3；≥12→4。gridPrecision 优先；皆缺 → 2。 */
	static int resolvePrecision(Integer zoom, Integer gridPrecision) {
		if (gridPrecision != null) {
			if (gridPrecision < 1 || gridPrecision > TrackConstants.GEO_SCALE) {
				throw new ServiceException("网格精度只支持 1 到 " + TrackConstants.GEO_SCALE
					+ "。请改到这个范围里再查");
			}
			return gridPrecision;
		}
		if (zoom == null) {
			return TrackConstants.SPATIAL_DEFAULT_PRECISION;
		}
		if (zoom < 1 || zoom > 20) {
			throw new ServiceException("缩放级别只支持 1 到 20。请改到这个范围里再查");
		}
		if (zoom <= 5) {
			return 1;
		}
		if (zoom <= 8) {
			return 2;
		}
		if (zoom <= 11) {
			return 3;
		}
		return TrackConstants.GEO_SCALE;
	}

	/**
	 * 解析 bbox；非法或缺字段 → 400；minLon&gt;maxLon（跨 180°）不支持。
	 *
	 * @return null 表示未传；否则 [minLon,minLat,maxLon,maxLat]
	 */
	static double[] parseBbox(String bbox) {
		if (bbox == null || bbox.isBlank()) {
			return null;
		}
		String[] parts = bbox.split(",", -1);
		if (parts.length != 4) {
			throw new ServiceException("查询范围格式不对。请用 minLon,minLat,maxLon,maxLat（WGS84）");
		}
		double minLon;
		double minLat;
		double maxLon;
		double maxLat;
		try {
			minLon = Double.parseDouble(parts[0].trim());
			minLat = Double.parseDouble(parts[1].trim());
			maxLon = Double.parseDouble(parts[2].trim());
			maxLat = Double.parseDouble(parts[3].trim());
		} catch (NumberFormatException e) {
			throw new ServiceException("查询范围不是合法数字。请用 minLon,minLat,maxLon,maxLat（WGS84）");
		}
		if (!Double.isFinite(minLon) || !Double.isFinite(minLat)
			|| !Double.isFinite(maxLon) || !Double.isFinite(maxLat)) {
			throw new ServiceException("查询范围包含非有限值。请改成合法经纬度后再查");
		}
		if (minLon < -180d || maxLon > 180d || minLat < -90d || maxLat > 90d
			|| minLon > 180d || maxLon < -180d || minLat > 90d || maxLat < -90d) {
			throw new ServiceException("查询范围超出 WGS84。经度 -180～180，纬度 -90～90");
		}
		if (minLon > maxLon) {
			throw new ServiceException("本接口不支持跨 180° 经线的范围。请拆成两个不跨界的框再查");
		}
		if (minLat > maxLat) {
			throw new ServiceException("查询范围纬度上下颠倒了。请保证 minLat 小于等于 maxLat");
		}
		return new double[] { minLon, minLat, maxLon, maxLat };
	}

	private Map<String, Object> queryAgg(String appKey, String tenant, OffsetDateTime from,
										double[] box, List<String> names, int precision) {
		List<Object> args = new ArrayList<>();
		StringBuilder where = new StringBuilder(
			" FROM track_event e WHERE e.app_key = ? AND e.received_at >= ?"
				+ " AND e.geo_lon IS NOT NULL AND e.geo_lat IS NOT NULL");
		args.add(appKey);
		args.add(from);
		where.append(tenantFrag("e", tenant, args));
		appendBbox(where, args, box);
		appendEventNames(where, args, names);

		List<Object> countArgs = new ArrayList<>(args);
		Long geoCount = jdbc.queryForObject("SELECT count(*)" + where, Long.class, countArgs.toArray());
		long total = geoCount == null ? 0L : geoCount;

		List<Object> cellArgs = new ArrayList<>();
		cellArgs.add(precision);
		cellArgs.add(precision);
		cellArgs.addAll(args);
		int fetch = TrackConstants.GEO_CELLS_MAX + 1;
		List<Map<String, Object>> rows = jdbc.queryForList(
			"SELECT round(e.geo_lon::numeric, ?) AS lon, round(e.geo_lat::numeric, ?) AS lat,"
				+ " count(*) AS count"
				+ where
				+ " GROUP BY 1, 2 ORDER BY count DESC, lon, lat LIMIT " + fetch,
			cellArgs.toArray());
		boolean truncated = rows.size() > TrackConstants.GEO_CELLS_MAX;
		if (truncated) {
			rows = rows.subList(0, TrackConstants.GEO_CELLS_MAX);
		}
		List<Map<String, Object>> cells = new ArrayList<>(rows.size());
		for (Map<String, Object> row : rows) {
			long count = num(row.get("count"));
			Map<String, Object> cell = new LinkedHashMap<>();
			cell.put("lon", row.get("lon"));
			cell.put("lat", row.get("lat"));
			cell.put("count", count);
			cell.put("weight", count);
			cells.add(cell);
		}
		Map<String, Object> result = new LinkedHashMap<>();
		result.put("mode", MODE_AGG);
		result.put("cells", cells);
		result.put("geoCount", total);
		result.put("truncated", truncated);
		return result;
	}

	private Map<String, Object> queryPoints(String appKey, String tenant, OffsetDateTime from,
											double[] box, List<String> names) {
		List<Object> args = new ArrayList<>();
		StringBuilder cond = new StringBuilder(
			" WHERE e.app_key = ? AND e.received_at >= ?"
				+ " AND e.geo_lon IS NOT NULL AND e.geo_lat IS NOT NULL");
		args.add(appKey);
		args.add(from);
		cond.append(tenantFrag("e", tenant, args));
		appendBbox(cond, args, box);
		appendEventNames(cond, args, names);

		List<Object> countArgs = new ArrayList<>(args);
		Long geoCount = jdbc.queryForObject("SELECT count(*) FROM track_event e" + cond,
			Long.class, countArgs.toArray());
		long total = geoCount == null ? 0L : geoCount;

		int fetch = TrackConstants.GEO_POINTS_MAX + 1;
		List<Map<String, Object>> rows = jdbc.queryForList(
			"SELECT e.geo_lon AS lon, e.geo_lat AS lat, e.event_name AS \"eventName\","
				+ " CAST(EXTRACT(EPOCH FROM e.ts) * 1000 AS BIGINT) AS ts,"
				+ " e.url_path AS \"urlPath\", e.event_id AS \"eventId\","
				+ " e.session_id AS \"sessionId\", e.distinct_id AS \"distinctId\","
				+ " coalesce(m.user_id::text, e.user_id::text) AS \"userId\""
				+ " FROM track_event e"
				+ " LEFT JOIN track_identity m ON m.app_key = e.app_key"
				+ " AND m.distinct_id = e.distinct_id AND m.is_deleted = 0"
				+ cond
				+ " ORDER BY e.received_at DESC LIMIT " + fetch,
			args.toArray());
		boolean truncated = rows.size() > TrackConstants.GEO_POINTS_MAX;
		if (truncated) {
			rows = rows.subList(0, TrackConstants.GEO_POINTS_MAX);
		}
		List<Map<String, Object>> points = new ArrayList<>(rows.size());
		for (Map<String, Object> row : rows) {
			Map<String, Object> point = new LinkedHashMap<>();
			point.put("lon", row.get("lon"));
			point.put("lat", row.get("lat"));
			point.put("eventName", row.get("eventName"));
			point.put("ts", row.get("ts"));
			putOptional(point, "urlPath", row.get("urlPath"));
			putOptional(point, "eventId", row.get("eventId"));
			putOptional(point, "sessionId", row.get("sessionId"));
			putOptional(point, "distinctId", row.get("distinctId"));
			putOptional(point, "userId", row.get("userId"));
			// 无漏斗定义：即使请求了 funnel* 也一律 false
			point.put("converted", false);
			points.add(point);
		}
		Map<String, Object> result = new LinkedHashMap<>();
		result.put("mode", MODE_POINTS);
		result.put("points", points);
		result.put("geoCount", total);
		result.put("truncated", truncated);
		return result;
	}

	private static Map<String, Object> emptyResult(String mode) {
		Map<String, Object> result = new LinkedHashMap<>();
		result.put("mode", mode);
		if (MODE_POINTS.equals(mode)) {
			result.put("points", List.of());
		} else {
			result.put("cells", List.of());
		}
		result.put("geoCount", 0L);
		result.put("truncated", false);
		return result;
	}

	private void assertRateLimit() {
		long userId;
		try {
			userId = StpUtil.getLoginIdAsLong();
		} catch (RuntimeException e) {
			// 无登录上下文时由 @SaCheckLogin 兜底；此处不额外限流
			return;
		}
		String key = TrackConstants.SPATIAL_RATE_LIMIT_KEY_PREFIX + userId;
		long now = Instant.now().toEpochMilli();
		long windowMs = TrackConstants.SPATIAL_RATE_LIMIT_WINDOW_SECONDS * 1000L;
		String member = now + ":" + UUID.randomUUID();
		try {
			redis.opsForZSet().removeRangeByScore(key, 0, now - windowMs);
			Long size = redis.opsForZSet().zCard(key);
			if (size != null && size >= TrackConstants.SPATIAL_RATE_LIMIT) {
				throw new TrackSpatialRateLimitException("请求过于频繁。请等一分钟后再查空间分析");
			}
			redis.opsForZSet().add(key, member, now);
			redis.expire(key, Duration.ofSeconds(TrackConstants.SPATIAL_RATE_LIMIT_EXPIRE_SECONDS));
		} catch (TrackSpatialRateLimitException e) {
			throw e;
		} catch (RuntimeException e) {
			log.warn("空间分析限流 Redis 不可用，本次放行：{}", e.getMessage());
		}
	}

	private static void appendBbox(StringBuilder where, List<Object> args, double[] box) {
		if (box == null) {
			return;
		}
		where.append(" AND e.geo_lon >= ? AND e.geo_lat >= ? AND e.geo_lon <= ? AND e.geo_lat <= ?");
		args.add(box[0]);
		args.add(box[1]);
		args.add(box[2]);
		args.add(box[3]);
	}

	private static void appendEventNames(StringBuilder where, List<Object> args, List<String> names) {
		if (names == null || names.isEmpty()) {
			return;
		}
		where.append(" AND e.event_name IN (").append("?, ".repeat(names.size() - 1)).append("?)");
		args.addAll(names);
	}

	private static List<String> parseEventNames(String eventNames) {
		if (eventNames == null || eventNames.isBlank()) {
			return List.of();
		}
		List<String> out = new ArrayList<>();
		for (String part : eventNames.split(",")) {
			String name = part.trim();
			if (name.isEmpty()) {
				continue;
			}
			if (!TrackConstants.CUSTOM_EVENT_NAME.matcher(name).matches()
				&& !TrackConstants.PREDEFINED_EVENTS.contains(name)) {
				throw new ServiceException("事件名不合法。请从事件列表里重新选择");
			}
			if (!out.contains(name)) {
				out.add(name);
			}
		}
		return List.copyOf(out);
	}

	/** 校验 funnelSteps 为逗号分隔正整数；本刀不解析为事件（无定义表）。 */
	private static void parseFunnelSteps(String funnelSteps) {
		if (funnelSteps == null || funnelSteps.isBlank()) {
			return;
		}
		for (String part : funnelSteps.split(",")) {
			String s = part.trim();
			if (s.isEmpty()) {
				continue;
			}
			try {
				int step = Integer.parseInt(s);
				if (step < 1) {
					throw new ServiceException("漏斗步骤须为正整数。请从 1 开始编号");
				}
			} catch (NumberFormatException e) {
				throw new ServiceException("漏斗步骤须为正整数。请用逗号分隔的步骤编号");
			}
		}
	}

	private static void assertWindowOptional(Long windowHours) {
		if (windowHours == null) {
			return;
		}
		if (!TrackConstants.FUNNEL_WINDOW_OPTIONS_HOURS.contains(windowHours)) {
			throw new ServiceException("转化窗口只支持 1 小时、24 小时或 7 天。请从列表里重新选择");
		}
	}

	private static String assertMode(String mode) {
		if (mode == null || mode.isBlank()) {
			return MODE_AGG;
		}
		String m = mode.trim().toLowerCase(Locale.ROOT);
		if (!MODE_AGG.equals(m) && !MODE_POINTS.equals(m)) {
			throw new ServiceException("模式只支持 agg 或 points。请重新选择");
		}
		return m;
	}

	private int assertDays(Integer days) {
		if (days == null) {
			return 7;
		}
		if (days < 1 || days > TrackConstants.SPATIAL_DAYS_MAX) {
			throw new ServiceException("统计天数只支持 1 到 " + TrackConstants.SPATIAL_DAYS_MAX
				+ " 天。请改到这个范围里再查");
		}
		return days;
	}

	private String currentTenant() {
		Object[] ids = TenantContext.resolveTenantIds();
		return ids == null ? null : String.valueOf(ids[0]);
	}

	private String tenantFrag(String alias, String tenant, List<Object> args) {
		if (tenant == null) {
			return "";
		}
		args.add(tenant);
		return " AND " + (alias == null ? "" : alias + ".") + "tenant_id = ?";
	}

	private void assertAppVisible(String appKey, String tenant) {
		List<Object> args = new ArrayList<>(List.of(appKey));
		String sql = "SELECT count(*) FROM track_app WHERE app_key = ? AND is_deleted = 0"
			+ tenantFrag(null, tenant, args);
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
		Long found = jdbc.queryForObject(
			"SELECT count(*) FROM track_app WHERE app_key = ? AND is_deleted = 0", Long.class, key);
		if (found == null || found == 0) {
			throw new ServiceException("这个应用不存在。请刷新应用列表后再看");
		}
		return key;
	}

	private static long num(Object value) {
		return value == null ? 0L : ((Number) value).longValue();
	}

	private static void putOptional(Map<String, Object> target, String key, Object value) {
		if (value == null) {
			return;
		}
		String text = String.valueOf(value);
		if (text.isEmpty() || "null".equalsIgnoreCase(text)) {
			return;
		}
		target.put(key, text);
	}
}
