package com.mugsun.boot.track;

import com.alicp.jetcache.anno.CacheType;
import com.alicp.jetcache.anno.Cached;
import com.mugsun.boot.common.constant.TrackConstants;
import com.mugsun.boot.tenant.TenantContext;
import com.mugsun.core.tool.exception.ServiceException;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import javax.sql.DataSource;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 留存分析查询服务（G103，§20）：新客留存（首访日起）明细限窗即席查询（不进 rollup，同漏斗）。
 * <p><b>口径</b>（§20.1 钉死）：行为主体 actor = identity 归并 {@code coalesce(m.user_id::text, e.distinct_id)}
 * （与 UV 同口径）；cohort = 窗口内<b>新客</b>（actor 首活跃日落在 cohort 窗口内 <b>且</b> 首活跃日 &gt; 回看窗首日——
 * 回看窗 {@value TrackConstants#RETENTION_LOOKBACK_DAYS} 天，首日截断的 actor 保守排除，宁可漏不可假新客）；
 * 活跃 = 当天有任意事件（不加事件过滤，与「活跃」字面一致）；回访网格 = cohort 日 × D+0..D+(days-1)。
 * <p><b>时间</b>：日切一律 UTC 墙钟（{@code (received_at AT TIME ZONE 'UTC')::date}，与 rollup 时间规约 §17 一致）；
 * cohort 窗口 = [todayUtc-(days-1), todayUtc]，days 硬限 {@value TrackConstants#RETENTION_DAYS_MAX} 天。
 * <p><b>SQL</b>（§20.2 单窗双扫）：单次 SQL 多层 CTE——ev（[回看窗首日, now] actor/day 去重集，identity LEFT JOIN）
 * → first（actor min(day)）→ cohort（首活跃日 ∈ cohort 窗 且 &gt; 回看窗首日）→ retained（cohort JOIN ev ON actor 相同
 * 且 day BETWEEN first_day AND first_day+days-1）→ 按 first_day/day 聚合；仅返回 cohortSize&gt;0 的行。
 * 参数全占位符，命中人查索引 + 分区裁剪。
 * <p><b>租户隔离/缓存</b>：与 TrackFunnelService 同范式（显式租户条件 fail-closed + assertAppVisible；
 * JetCache LOCAL {@value TrackConstants#ANALYSIS_CACHE_SECONDS}s，自注入代理解 AOP）。
 */
@Service
@TrackDS
public class TrackRetentionService {

	/** 身份归并 JOIN 片段（actor 精确口径；表别名固定 e/m，与 TrackAnalysisService 同片段） */
	private static final String IDENTITY_JOIN = " LEFT JOIN track_identity m"
		+ " ON m.app_key = e.app_key AND m.distinct_id = e.distinct_id AND m.is_deleted = 0";

	private final JdbcTemplate jdbc;
	private final ObjectProvider<TrackRetentionService> self;

	public TrackRetentionService(DataSource dataSource, ObjectProvider<TrackRetentionService> self) {
		this.jdbc = new JdbcTemplate(dataSource);
		this.self = self;
	}

	/** 留存查询：{rows:[{cohortDate:"yyyy-MM-dd", cohortSize, retained:{offset数字字符串: n}}...按 cohortDate 升序], days} */
	public Map<String, Object> retention(String appKey, Integer days) {
		assertAppKey(appKey);
		int d = clampDays(days);
		String tenant = currentTenant();
		assertAppVisible(appKey, tenant);
		return self.getObject().cachedRetention(appKey, d, tenant);
	}

	@Cached(name = "track:ana:retention:", key = "#appKey + ':' + #days",
		expire = TrackConstants.ANALYSIS_CACHE_SECONDS, cacheType = CacheType.LOCAL)
	public Map<String, Object> cachedRetention(String appKey, int days, String tenant) {
		OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
		LocalDate todayUtc = now.toLocalDate();
		LocalDate cohortStart = todayUtc.minusDays(days - 1L);
		LocalDate lookbackStart = cohortStart.minusDays(TrackConstants.RETENTION_LOOKBACK_DAYS);
		OffsetDateTime evFrom = lookbackStart.atStartOfDay().atOffset(ZoneOffset.UTC);

		StringBuilder sql = new StringBuilder(
			"WITH ev AS (SELECT DISTINCT coalesce(m.user_id::text, e.distinct_id) AS actor,"
				+ " (e.received_at AT TIME ZONE 'UTC')::date AS day"
				+ " FROM track_event e" + IDENTITY_JOIN
				+ " WHERE e.app_key = ? AND e.received_at >= ? AND e.received_at <= ?");
		List<Object> args = new ArrayList<>(List.of(appKey, evFrom, now));
		sql.append(tenantFrag("e", tenant, args));
		sql.append("), first AS (SELECT actor, min(day) AS first_day FROM ev GROUP BY actor)");
		// cohort：首活跃日 ∈ cohort 窗 且 > 回看窗首日（首日截断无法判定新老，保守排除——宁漏不假新客）
		sql.append(", cohort AS (SELECT actor, first_day FROM first"
			+ " WHERE first_day >= ? AND first_day <= ? AND first_day > ?)");
		args.add(cohortStart);
		args.add(todayUtc);
		args.add(lookbackStart);
		sql.append(", cs AS (SELECT first_day, count(*) AS cohort_size FROM cohort GROUP BY first_day)");
		// retained：cohort  actor 在 [first_day, first_day+days-1] 内的活跃日（D+0 恒存在 = cohort_size）
		sql.append(", retained AS (SELECT c.first_day, (ev.day - c.first_day) AS day_offset, count(*) AS n"
			+ " FROM cohort c JOIN ev ON ev.actor = c.actor"
			+ " AND ev.day BETWEEN c.first_day AND c.first_day + ?"
			+ " GROUP BY c.first_day, ev.day)");
		args.add(days - 1);
		sql.append(" SELECT to_char(cs.first_day, 'YYYY-MM-DD') AS \"cohortDate\", cs.cohort_size AS \"cohortSize\","
			+ " r.day_offset AS \"offset\", r.n FROM cs JOIN retained r ON r.first_day = cs.first_day"
			+ " ORDER BY cs.first_day, r.day_offset");

		List<Map<String, Object>> grid = jdbc.queryForList(sql.toString(), args.toArray());

		// 聚合行（first_day × offset）→ 每 cohort 日一行：retained map 按 offset 升序（SQL 已保序，LinkedHashMap 承接）
		List<Map<String, Object>> rows = new ArrayList<>();
		Map<String, Object> current = null;
		Map<String, Long> currentRetained = null;
		String currentDate = null;
		for (Map<String, Object> row : grid) {
			String date = (String) row.get("cohortDate");
			if (!date.equals(currentDate)) {
				currentDate = date;
				currentRetained = new LinkedHashMap<>();
				current = new LinkedHashMap<>();
				current.put("cohortDate", date);
				Object size = row.get("cohortSize");
				current.put("cohortSize", size == null ? 0L : ((Number) size).longValue());
				current.put("retained", currentRetained);
				rows.add(current);
			}
			Object n = row.get("n");
			currentRetained.put(String.valueOf(((Number) row.get("offset")).intValue()),
				n == null ? 0L : ((Number) n).longValue());
		}

		Map<String, Object> result = new LinkedHashMap<>();
		result.put("rows", rows);
		result.put("days", days);
		return result;
	}

	// ==================== 内部工具 ====================

	/** days 钳制：缺省/非法 7 天，上限 {@value TrackConstants#RETENTION_DAYS_MAX}（照 clampDays 模式） */
	private int clampDays(Integer days) {
		if (days == null || days < 1) {
			return 7;
		}
		return Math.min(days, TrackConstants.RETENTION_DAYS_MAX);
	}

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

	/** 应用可见性（0 行 = 不存在或他租户，400；跨租户查询 fail-closed 而非返回他租户全零假象） */
	private void assertAppVisible(String appKey, String tenant) {
		List<Object> args = new ArrayList<>(List.of(appKey));
		String sql = "SELECT count(*) FROM track_app WHERE app_key = ? AND is_deleted = 0" + tenantFrag(null, tenant, args);
		Long count = jdbc.queryForObject(sql, Long.class, args.toArray());
		if (count == null || count == 0) {
			throw new ServiceException("应用不存在或无权访问");
		}
	}

	private void assertAppKey(String appKey) {
		if (appKey == null || appKey.isBlank() || appKey.length() > TrackConstants.APP_KEY_MAX_LEN) {
			throw new ServiceException("缺少或非法 appKey");
		}
	}
}
