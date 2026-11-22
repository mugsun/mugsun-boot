package com.mugsun.boot.track;

import com.alicp.jetcache.anno.CacheType;
import com.alicp.jetcache.anno.Cached;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mugsun.boot.common.constant.TrackConstants;
import com.mugsun.boot.tenant.TenantContext;
import com.mugsun.core.i18n.message.I18n;
import com.mugsun.core.tool.exception.ServiceException;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import javax.sql.DataSource;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 访问路径分析：选一个页面当起点，看这些会话下一步去了哪个页面、有多少在这里就没有再往下走；
 * 再选一个去向当第二步，接着往下看，最多 {@value TrackConstants#JOURNEY_STEPS_MAX} 步。
 *
 * <p><b>怎么算</b>
 * <ul>
 *   <li>按会话算，不按人算：一次会话里的页面浏览（{@code $pageview}）按发生时间排成一串。
 *       同一个人隔天再来是另一次会话，路径不会跨会话接起来。</li>
 *   <li>页面用路由模板（{@code route_path}），没有模板时用原始路径，和「页面排行」是同一个取法。</li>
 *   <li>连着几次都是同一个页面（刷新）只算一次，所以相邻两步不会是同一个页面。</li>
 *   <li>步骤之间要紧挨着：选了 A、B 两步，只算 A 之后紧接着就是 B 的会话。这和漏斗不一样，
 *       漏斗允许中间穿插别的事件；这里要回答的是「下一步」，允许穿插就没有意义了。</li>
 *   <li>每次会话只从起点页面第一次出现的地方往后看（起点方式是入口页时，就是会话的第一个页面）。
 *       后面的步骤接不上，这次会话就不算走了这条路径，哪怕它后来又回到起点把这条路径走了一遍。
 *       这样一次会话在每一步只算一次，数就对得上：各个去向的会话数、没有下一步的会话数、
 *       排不进列表的会话数加起来，正好等于最后一步的会话数；把某个去向选成下一步以后，
 *       这一步的会话数就是刚才列表里那一项的数。</li>
 *   <li>统计范围和漏斗一样：按租户时区切日，从首日当地 0 点到现在；用服务端接收时间圈范围（能按分区裁剪），
 *       用发生时间排先后（同一批上报的事件接收时间相同，分不出先后）。发生时间相同的按主键排。</li>
 *   <li>会话跨过统计范围的起点时，只看得到范围内的那一段。</li>
 * </ul>
 *
 * <p><b>为什么直接查明细</b>：起点和每一步都是临时选的，没法提前汇总，和漏斗一样只能查明细，
 * 所以天数限在 {@value TrackConstants#JOURNEY_DAYS_MAX} 天以内，查询命中
 * {@code idx_event_query (app_key, event_name, received_at)}。
 *
 * <p><b>租户隔离</b>：和 {@link TrackFunnelService} 同一套做法——先校验应用归属，SQL 里再显式带租户条件；
 * 没有租户上下文时直接报错，不会查出别的租户的数据。页面地址等所有入参都走占位符，不拼进 SQL。
 *
 * <p><b>缓存</b>：本机缓存 {@value TrackConstants#ANALYSIS_CACHE_SECONDS} 秒，键里带租户。
 */
@Service
@TrackDS
public class TrackJourneyService {

	/** 返回值里的统计单位：会话 */
	private static final String UNIT_SESSION = "session";

	/** 页面的取法，和 TrackAnalysisService 的页面排行一致：路由模板优先，没有就用原始路径 */
	private static final String PAGE_EXPR =
		"left(coalesce(nullif(e.route_path, ''), e.url_path), " + TrackConstants.DIM_MAX_LEN + ")";

	/** 只用来读写步骤数组，不需要全局解析器上的那些定制 */
	private static final ObjectMapper PLAIN = new ObjectMapper();
	private static final TypeReference<List<String>> STEP_LIST = new TypeReference<>() {
	};

	private final JdbcTemplate jdbc;
	private final ObjectProvider<TrackJourneyService> self;
	private final TrackTimezoneProperties timezones;

	public TrackJourneyService(DataSource dataSource, ObjectProvider<TrackJourneyService> self,
							   TrackTimezoneProperties timezones) {
		this.jdbc = new JdbcTemplate(dataSource);
		this.self = self;
		this.timezones = timezones;
	}

	/**
	 * 查一条路径的下一步。
	 *
	 * @param appKey 应用标识，必填
	 * @param steps  已经选好的步骤，JSON 数组（如 {@code ["/login","/home"]}）；不传或空数组表示还没选起点。
	 *               用 JSON 而不用逗号分隔，是因为页面地址里可以有逗号
	 * @param days   统计天数，默认 7，只支持 1 到 {@value TrackConstants#JOURNEY_DAYS_MAX}
	 * @param start  起点方式：{@code any}（默认）路径可以从会话的任意位置开始；
	 *               {@code entry} 路径必须从会话的第一个页面开始
	 * @param limit  列出多少个去向，默认 {@value TrackConstants#JOURNEY_NEXT_LIMIT_DEFAULT}，
	 *               最多 {@value TrackConstants#JOURNEY_NEXT_LIMIT_MAX}
	 * @return {@code days}、{@code start}、{@code unit}（固定是 session）；
	 *         {@code total} 统计范围内有页面浏览的会话数；
	 *         {@code steps} 已选的每一步和走到这一步的会话数 {@code [{page, sessions}]}；
	 *         {@code next} 下一步的去向 {@code [{page, sessions}]}，按会话数从多到少，还没选起点时是可以当起点的页面；
	 *         {@code ended} 走完已选的步骤以后没有下一步的会话数；
	 *         {@code others} 去向排不进 {@code next} 的会话数。
	 *         选了步骤以后，{@code next} 的会话数之和 + {@code ended} + {@code others} = 最后一步的会话数；
	 *         把 {@code next} 里的某一项接到 {@code steps} 后面再查，新的最后一步的会话数就是这一项的数。
	 *         还没选起点时 {@code ended} 恒为 0；{@code others} 只在 {@code start=entry} 时有数
	 *         （每次会话只有一个入口页），{@code start=any} 时一次会话会算进好几个页面，恒为 0
	 */
	public Map<String, Object> journey(String appKey, String steps, Integer days, String start, Integer limit) {
		appKey = assertAppKey(appKey);
		List<String> stepList = parseSteps(steps);
		int d = assertDays(days);
		String startMode = assertStart(start);
		int top = clampLimit(limit);
		String tenant = currentTenant();
		assertAppVisible(appKey, tenant);
		return self.getObject().cachedJourney(appKey, writeSteps(stepList), d, startMode, top, tenant,
			timezones.zoneFor(tenant).getId());
	}

	@Cached(name = "track:ana:journey:",
		key = "#appKey + ':' + #days + ':' + #start + ':' + #limit + ':' + #stepsJson + ':' + #tenant + ':' + #zoneId",
		expire = TrackConstants.ANALYSIS_CACHE_SECONDS, cacheType = CacheType.LOCAL)
	public Map<String, Object> cachedJourney(String appKey, String stepsJson, int days, String start, int limit,
											 String tenant, String zoneId) {
		List<String> steps = readSteps(stepsJson);
		boolean fromEntry = TrackConstants.JOURNEY_START_ENTRY.equals(start);
		int n = steps.size();
		OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
		OffsetDateTime from = TrackDayWindow.of(ZoneId.of(zoneId), now.toInstant(), days).fromOffset();

		// seq：每次会话的页面序列。先去掉没有页面地址的，再把连着重复的同一个页面合成一次，最后从 1 开始编号
		StringBuilder sql = new StringBuilder("WITH pv AS (SELECT e.session_id, " + PAGE_EXPR + " AS page, e.ts, e.id"
			+ " FROM track_event e WHERE e.app_key = ? AND e.received_at >= ? AND e.received_at <= ?");
		List<Object> args = new ArrayList<>(List.of(appKey, from, now));
		sql.append(tenantFrag("e", tenant, args));
		sql.append(" AND e.event_name = ?)");
		args.add(TrackConstants.EVENT_PAGEVIEW);
		sql.append(", marked AS (SELECT session_id, page, ts, id,"
			+ " lag(page) OVER (PARTITION BY session_id ORDER BY ts, id) AS prev FROM pv WHERE page <> '')");
		sql.append(", seq AS (SELECT session_id, page,"
			+ " row_number() OVER (PARTITION BY session_id ORDER BY ts, id) AS pos"
			+ " FROM marked WHERE prev IS DISTINCT FROM page)");

		if (n == 0) {
			// 还没选起点：列出可以当起点的页面。any 按「访问过这个页面的会话数」，entry 按「从这个页面进来的会话数」
			sql.append(", top AS (SELECT page, count(DISTINCT session_id) AS sessions FROM seq");
			if (fromEntry) {
				sql.append(" WHERE pos = 1");
			}
			sql.append(" GROUP BY page ORDER BY sessions DESC, page LIMIT ?)");
			args.add(limit);
			sql.append(", cnt AS (SELECT count(DISTINCT session_id) AS total FROM seq)");
		} else {
			// c1：每次会话里起点页面第一次出现的位置（入口页方式只认会话的第一个页面），一次会话最多一行。
			// cK：从这个位置起前 K 步都对得上的会话。第 K 步必须紧挨在起始位置后面第 K-1 个
			sql.append(", c1 AS (SELECT session_id, min(pos) AS pos FROM seq WHERE page = ?");
			args.add(steps.get(0));
			if (fromEntry) {
				sql.append(" AND pos = 1");
			}
			sql.append(" GROUP BY session_id)");
			for (int k = 2; k <= n; k++) {
				sql.append(", c").append(k).append(" AS (SELECT c.session_id, c.pos FROM c").append(k - 1)
					.append(" c JOIN seq s ON s.session_id = c.session_id AND s.pos = c.pos + ").append(k - 1)
					.append(" AND s.page = ?)");
				args.add(steps.get(k - 1));
			}
			// nx：走完已选的步骤以后紧跟着的页面，没有就是 NULL
			sql.append(", nx AS (SELECT s.page, count(*) AS sessions FROM c").append(n).append(" m LEFT JOIN seq s"
				+ " ON s.session_id = m.session_id AND s.pos = m.pos + ").append(n).append(" GROUP BY s.page)");
			// NULL（没有下一步）排最前，保证它不会被 LIMIT 挡在外面，所以多取一行
			sql.append(", top AS (SELECT page, sessions FROM nx"
				+ " ORDER BY (page IS NULL) DESC, sessions DESC, page LIMIT ?)");
			args.add(limit + 1);
			sql.append(", cnt AS (SELECT (SELECT count(DISTINCT session_id) FROM seq) AS total");
			for (int k = 1; k <= n; k++) {
				sql.append(", (SELECT count(*) FROM c").append(k).append(") AS n").append(k);
			}
			sql.append(")");
		}
		// cnt 恒为一行，左连 top：一个去向都没有时仍然拿得到各项计数（这时 page、sessions 都是 NULL）
		sql.append(" SELECT top.page AS \"page\", top.sessions AS \"sessions\", cnt.total AS \"total\"");
		for (int k = 1; k <= n; k++) {
			sql.append(", cnt.n").append(k).append(" AS \"n").append(k).append("\"");
		}
		sql.append(" FROM cnt LEFT JOIN top ON true");

		List<Map<String, Object>> rows = jdbc.queryForList(sql.toString(), args.toArray());
		Map<String, Object> first = rows.get(0);
		long total = toLong(first.get("total"));

		long ended = 0L;
		List<Map<String, Object>> next = new ArrayList<>();
		for (Map<String, Object> row : rows) {
			Object sessions = row.get("sessions");
			if (sessions == null) {
				continue;
			}
			Object page = row.get("page");
			if (page == null) {
				ended = toLong(sessions);
				continue;
			}
			Map<String, Object> item = new LinkedHashMap<>();
			item.put("page", page.toString());
			item.put("sessions", toLong(sessions));
			next.add(item);
		}
		// 左连以后行的次序不保证，这里重排：会话数从多到少，一样多的按页面地址
		next.sort(Comparator.<Map<String, Object>>comparingLong(item -> -toLong(item.get("sessions")))
			.thenComparing(item -> (String) item.get("page")));
		if (next.size() > limit) {
			next = new ArrayList<>(next.subList(0, limit));
		}
		long shown = 0L;
		for (Map<String, Object> item : next) {
			shown += toLong(item.get("sessions"));
		}

		List<Map<String, Object>> stepRows = new ArrayList<>();
		for (int k = 1; k <= n; k++) {
			Map<String, Object> step = new LinkedHashMap<>();
			step.put("page", steps.get(k - 1));
			step.put("sessions", toLong(first.get("n" + k)));
			stepRows.add(step);
		}

		long others;
		if (n > 0) {
			others = toLong(first.get("n" + n)) - ended - shown;
		} else {
			others = fromEntry ? total - shown : 0L;
		}

		Map<String, Object> result = new LinkedHashMap<>();
		result.put("days", days);
		result.put("start", start);
		result.put("unit", UNIT_SESSION);
		result.put("total", total);
		result.put("steps", stepRows);
		result.put("next", next);
		result.put("ended", ended);
		result.put("others", Math.max(others, 0L));
		return result;
	}

	// ==================== 入参校验 ====================

	/**
	 * 步骤：JSON 字符串数组，0 到 {@value TrackConstants#JOURNEY_STEPS_MAX} 个；每个都是页面地址，
	 * 不能为空、不能超过 {@value TrackConstants#DIM_MAX_LEN} 个字符、不能带控制字符；相邻两步不能相同。
	 * 地址原样比较，不去空格、不改大小写
	 */
	private List<String> parseSteps(String steps) {
		if (steps == null || steps.isBlank()) {
			return List.of();
		}
		JsonNode root;
		try {
			root = PLAIN.readTree(steps);
		} catch (JsonProcessingException e) {
			throw new ServiceException("路径步骤的格式不对。请在页面上重新选择起点");
		}
		if (root == null || !root.isArray()) {
			throw new ServiceException("路径步骤的格式不对。请在页面上重新选择起点");
		}
		if (root.size() > TrackConstants.JOURNEY_STEPS_MAX) {
			throw new ServiceException(I18n.get("路径最多选 {0} 步。请去掉后面的步骤再看", TrackConstants.JOURNEY_STEPS_MAX));
		}
		List<String> list = new ArrayList<>();
		for (JsonNode node : root) {
			if (!node.isTextual() || !isPage(node.asText())) {
				throw new ServiceException("路径里的页面地址不对。请从列表里重新选择");
			}
			String page = node.asText();
			if (!list.isEmpty() && list.get(list.size() - 1).equals(page)) {
				throw new ServiceException("相邻两步不能是同一个页面。请重新选择下一步");
			}
			list.add(page);
		}
		return List.copyOf(list);
	}

	private static boolean isPage(String page) {
		if (page.isBlank() || page.length() > TrackConstants.DIM_MAX_LEN) {
			return false;
		}
		return page.chars().noneMatch(Character::isISOControl);
	}

	/** 步骤写成固定形态的 JSON，当缓存键的一部分：同一组步骤不管入参里带不带空白，键都一样 */
	private static String writeSteps(List<String> steps) {
		try {
			return PLAIN.writeValueAsString(steps);
		} catch (JsonProcessingException e) {
			throw new IllegalStateException(e);
		}
	}

	private static List<String> readSteps(String stepsJson) {
		try {
			return PLAIN.readValue(stepsJson, STEP_LIST);
		} catch (JsonProcessingException e) {
			throw new IllegalStateException(e);
		}
	}

	/** 统计天数：缺省 7 天；须在 1..{@value TrackConstants#JOURNEY_DAYS_MAX}，否则说明重新选择 */
	private int assertDays(Integer days) {
		if (days == null) {
			return 7;
		}
		if (days < 1 || days > TrackConstants.JOURNEY_DAYS_MAX) {
			throw new ServiceException(I18n.get("统计天数只支持 1 到 {0} 天。请从今天、近 7 天或近 30 天里重新选择", TrackConstants.JOURNEY_DAYS_MAX));
		}
		return days;
	}

	private String assertStart(String start) {
		if (start == null || start.isBlank()) {
			return TrackConstants.JOURNEY_START_ANY;
		}
		if (TrackConstants.JOURNEY_START_ANY.equals(start) || TrackConstants.JOURNEY_START_ENTRY.equals(start)) {
			return start;
		}
		throw new ServiceException("起点方式只支持任意位置和入口页。请重新选择");
	}

	/** 去向个数：缺省或小于 1 用默认值，超过上限按上限 */
	private int clampLimit(Integer limit) {
		if (limit == null || limit < 1) {
			return TrackConstants.JOURNEY_NEXT_LIMIT_DEFAULT;
		}
		return Math.min(limit, TrackConstants.JOURNEY_NEXT_LIMIT_MAX);
	}

	private static long toLong(Object value) {
		return value == null ? 0L : ((Number) value).longValue();
	}

	// ==================== 租户和应用归属（和 TrackFunnelService 同一套做法） ====================

	/** 当前请求的租户：null 表示超级管理员看全部；没有租户上下文时由 TenantContext 直接抛错 */
	private String currentTenant() {
		Object[] ids = TenantContext.resolveTenantIds();
		return ids == null ? null : String.valueOf(ids[0]);
	}

	/** 租户条件：有租户就拼 {@code AND [别名.]tenant_id = ?} 并把值加进入参；看全部时不拼 */
	private String tenantFrag(String alias, String tenant, List<Object> args) {
		if (tenant == null) {
			return "";
		}
		args.add(tenant);
		return " AND " + (alias == null ? "" : alias + ".") + "tenant_id = ?";
	}

	/** 应用必须是当前租户的：查不到就报错，不返回一份全是 0 的结果让人误以为没有数据 */
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
			throw new ServiceException("请先选择应用。访问路径是按应用分开的");
		}
		String key = appKey.trim();
		if (key.length() > TrackConstants.APP_KEY_MAX_LEN) {
			throw new ServiceException("应用标识不对。请从应用列表里重新选择");
		}
		// 这一次不带租户条件：完全没有这个应用才说「不存在」；别的租户有同名应用时，由后面的归属校验说「无权访问」
		Long found = jdbc.queryForObject(
			"SELECT count(*) FROM track_app WHERE app_key = ? AND is_deleted = 0", Long.class, key);
		if (found == null || found == 0) {
			throw new ServiceException("这个应用不存在。请刷新应用列表后再看");
		}
		return key;
	}
}
