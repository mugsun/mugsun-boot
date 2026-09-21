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
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

/**
 * 漏斗分析查询服务（G103，§20）：明细限窗即席查询（不进 rollup——维度自由的任意步骤组合预聚合无法覆盖）。
 * <p><b>口径</b>（§20.1 钉死）：行为主体 actor = identity 归并 {@code coalesce(m.user_id::text, e.distinct_id)}
 * （与 UV 同口径，登录前后行为归并到同一人，不提供口径切换）；<b>有序非紧邻</b>匹配（中间可穿插其他事件）；
 * <b>转化窗口</b> = 每层步骤须在前一步触达后 windowHours 小时内触达（§20.2 逐层 JOIN 链 SQL 形态：
 * {@code ts >= tK-1 AND ts <= tK-1 + make_interval(hours => ?)}）；窗口外/乱序不算。
 * 事件窗口 = [now-days, now]，明细扫描硬限 {@value TrackConstants#FUNNEL_DAYS_MAX} 天（§2「明细下钻限时分区」）。
 * <p><b>排序键 = ts（校时后发生时间），非 received_at</b>（G103 浏览器审查实证修正）：SDK 批量上报（5s/10 条
 * 触发）使同批事件 received_at 相同（服务端毫秒），严格 {@code received_at > t_prev} 会误杀同批内有序链条——
 * 真实短漏斗（注册→验证→完成 5s 内）必中招；§4.2 定义 ts 即「分析用发生时间（校时修正后，下钻口径）」，
 * 漏斗正是下钻。非紧邻语义下 tie 取 {@code >=}（同毫秒先后不可知，宽容转化与 PostHog 非严格序同款）；
 * received_at 仍用于事件窗口过滤（分区裁剪 + 服务端时间防客户端 ts 伪造逃逸窗口）。
 * <p><b>SQL</b>（§20.2）：层数 = steps.size() 程序化生成 CTE 链（ev → s1 → ... → sN，逐层 JOIN 键 actor），
 * 事件名/窗口/时间全部占位符参数化，禁字符串拼接；命中 idx_event_query (app_key, event_name, received_at)。
 * <p><b>租户隔离</b>：JdbcTemplate 原生 SQL + 显式租户条件（与 TrackAnalysisService 同语义：
 * null=超管查看全部；无上下文 fail-closed 抛 {@code TenantException}）；先 {@link #assertAppVisible} 校验应用归属。
 * <p><b>缓存</b>：JetCache LOCAL {@value TrackConstants#ANALYSIS_CACHE_SECONDS}s
 * （tenantKeyConvertor 自动加租户前缀，缓存层租户隔离天然成立）；自注入代理解 AOP（TrackAnalysisService 同款）。
 */
@Service
@TrackDS
public class TrackFunnelService {

	/** 身份归并 JOIN 片段（actor 精确口径；表别名固定 e/m，与 TrackAnalysisService 同片段） */
	private static final String IDENTITY_JOIN = " LEFT JOIN track_identity m"
		+ " ON m.app_key = e.app_key AND m.distinct_id = e.distinct_id AND m.is_deleted = 0";

	/** 行为主体口径标记（返回值 actor 字段；v1 仅 identity 归并一种口径） */
	private static final String ACTOR_MODE_MERGED = "merged";

	private final JdbcTemplate jdbc;
	private final ObjectProvider<TrackFunnelService> self;

	public TrackFunnelService(DataSource dataSource, ObjectProvider<TrackFunnelService> self) {
		this.jdbc = new JdbcTemplate(dataSource);
		this.self = self;
	}

	/** 漏斗查询：{steps:[{eventName, count}...按入参序], days, windowHours, actor:"merged"}（转化率前端算） */
	public Map<String, Object> funnel(String appKey, String steps, Integer days, Long windowHours) {
		assertAppKey(appKey);
		List<String> stepList = parseSteps(steps);
		int d = clampDays(days);
		long window = assertWindow(windowHours);
		String tenant = currentTenant();
		assertAppVisible(appKey, tenant);
		return self.getObject().cachedFunnel(appKey, String.join(",", stepList), d, window, tenant);
	}

	@Cached(name = "track:ana:funnel:", key = "#appKey + ':' + #days + ':' + #windowHours + ':' + #steps",
		expire = TrackConstants.ANALYSIS_CACHE_SECONDS, cacheType = CacheType.LOCAL)
	public Map<String, Object> cachedFunnel(String appKey, String steps, int days, long windowHours, String tenant) {
		// 事件名已过 CUSTOM_EVENT_NAME 白名单（不含逗号），CSV 还原保序列表
		List<String> stepList = List.of(steps.split(","));
		OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
		OffsetDateTime from = now.minusDays(days);

		// ev CTE：窗口内 steps 事件全集（app_key + tenantFrag + received_at 窗 + event_name IN + identity LEFT JOIN）；
		// 排序/转化窗口用 ts（校时后发生时间，下钻口径），received_at 仅作窗口过滤（分区裁剪），见类 javadoc
		StringBuilder sql = new StringBuilder(
			"WITH ev AS (SELECT coalesce(m.user_id::text, e.distinct_id) AS actor, e.event_name, e.ts"
				+ " FROM track_event e" + IDENTITY_JOIN
				+ " WHERE e.app_key = ? AND e.received_at >= ? AND e.received_at <= ?");
		List<Object> args = new ArrayList<>(List.of(appKey, from, now));
		sql.append(tenantFrag("e", tenant, args));
		sql.append(" AND e.event_name IN (").append("?, ".repeat(stepList.size() - 1)).append("?))");
		args.addAll(stepList);

		// s1 = 各 actor 首次触达 step1 时刻
		sql.append(", s1 AS (SELECT actor, min(ts) AS t1 FROM ev WHERE event_name = ? GROUP BY actor)");
		args.add(stepList.get(0));
		// sK = 在 sK-1 触达后窗口小时内首次触达 stepK 的 actor（有序非紧邻；窗口外/乱序不进层）
		for (int k = 2; k <= stepList.size(); k++) {
			sql.append(", s").append(k).append(" AS (SELECT ev.actor, min(ev.ts) AS t").append(k)
				.append(" FROM ev JOIN s").append(k - 1).append(" ON ev.actor = s").append(k - 1).append(".actor")
				.append(" WHERE ev.event_name = ? AND ev.ts >= s").append(k - 1).append(".t").append(k - 1)
				.append(" AND ev.ts <= s").append(k - 1).append(".t").append(k - 1)
				.append(" + make_interval(hours => ?) GROUP BY ev.actor)");
			args.add(stepList.get(k - 1));
			args.add((int) windowHours);
		}
		// 最终逐层计数（标量子查询单行返回；count(sK) 即第 K 步转化人数）
		sql.append(" SELECT ");
		for (int k = 1; k <= stepList.size(); k++) {
			if (k > 1) {
				sql.append(", ");
			}
			sql.append("(SELECT count(*) FROM s").append(k).append(") AS \"c").append(k).append("\"");
		}

		Map<String, Object> counts = jdbc.queryForList(sql.toString(), args.toArray()).get(0);
		List<Map<String, Object>> stepRows = new ArrayList<>();
		for (int k = 1; k <= stepList.size(); k++) {
			Map<String, Object> step = new LinkedHashMap<>();
			step.put("eventName", stepList.get(k - 1));
			Object count = counts.get("c" + k);
			step.put("count", count == null ? 0L : ((Number) count).longValue());
			stepRows.add(step);
		}

		Map<String, Object> result = new LinkedHashMap<>();
		result.put("steps", stepRows);
		result.put("days", days);
		result.put("windowHours", windowHours);
		result.put("actor", ACTOR_MODE_MERGED);
		return result;
	}

	// ==================== 内部工具 ====================

	/** 步骤解析：逗号分隔 trim + 去重（保序）后 2..{@value TrackConstants#FUNNEL_STEPS_MAX} 个；
	 *  每个须满足 {@link TrackConstants#CUSTOM_EVENT_NAME} 或 ∈ {@link TrackConstants#PREDEFINED_EVENTS}，否则 400 */
	private List<String> parseSteps(String steps) {
		if (steps == null || steps.isBlank()) {
			throw new ServiceException("缺少 steps（逗号分隔事件名，2.." + TrackConstants.FUNNEL_STEPS_MAX + " 个）");
		}
		LinkedHashSet<String> deduped = new LinkedHashSet<>();
		for (String part : steps.split(",")) {
			String name = part.trim();
			if (!name.isEmpty()) {
				deduped.add(name);
			}
		}
		if (deduped.size() < 2 || deduped.size() > TrackConstants.FUNNEL_STEPS_MAX) {
			throw new ServiceException("步骤数须 2.." + TrackConstants.FUNNEL_STEPS_MAX + "（去重后）");
		}
		for (String name : deduped) {
			if (!TrackConstants.CUSTOM_EVENT_NAME.matcher(name).matches()
				&& !TrackConstants.PREDEFINED_EVENTS.contains(name)) {
				throw new ServiceException("步骤事件名不合法: " + name);
			}
		}
		return List.copyOf(deduped);
	}

	/** days 钳制：缺省/非法 7 天，上限 {@value TrackConstants#FUNNEL_DAYS_MAX}（照 clampDays 模式） */
	private int clampDays(Integer days) {
		if (days == null || days < 1) {
			return 7;
		}
		return Math.min(days, TrackConstants.FUNNEL_DAYS_MAX);
	}

	/** 转化窗口白名单：缺省 {@value TrackConstants#FUNNEL_WINDOW_DEFAULT_HOURS}h，
	 *  必须 ∈ {@link TrackConstants#FUNNEL_WINDOW_OPTIONS_HOURS}（1/24/168），否则 400 */
	private long assertWindow(Long windowHours) {
		if (windowHours == null) {
			return TrackConstants.FUNNEL_WINDOW_DEFAULT_HOURS;
		}
		if (!TrackConstants.FUNNEL_WINDOW_OPTIONS_HOURS.contains(windowHours)) {
			throw new ServiceException("转化窗口仅支持 " + TrackConstants.FUNNEL_WINDOW_OPTIONS_HOURS + " 小时");
		}
		return windowHours;
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
