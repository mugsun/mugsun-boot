package com.mugsun.boot.track;

import com.alicp.jetcache.anno.CacheType;
import com.alicp.jetcache.anno.Cached;
import com.mugsun.boot.common.constant.TrackConstants;
import com.mugsun.boot.tenant.TenantContext;
import com.mugsun.boot.track.entity.TrackGoal;
import com.mugsun.boot.track.mapper.TrackGoalMapper;
import com.mugsun.core.i18n.message.I18n;
import com.mugsun.core.tool.exception.ServiceException;
import com.mybatisflex.core.query.QueryWrapper;
import com.mybatisflex.core.update.UpdateChain;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import javax.sql.DataSource;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 目标转化：给应用定几个目标，看统计范围里有多少访客达成了、达成了多少次、占全部访客的比例。
 *
 * <p><b>目标怎么定</b>
 * <ul>
 *   <li>事件目标：触发了某个自定义事件就算达成。</li>
 *   <li>页面目标：访问了某个页面（一次页面浏览 {@code $pageview}）就算达成。页面地址和路由模板
 *       （{@code route_path}）或原始路径（{@code url_path}）任何一个对上都算，所以写 {@code /order/:id}
 *       或 {@code /order/123} 都可以；末尾加 {@code *} 表示这个地址开头的所有页面。</li>
 *   <li>目标只是一条定义，结果是查询时现算的：新建或改了条件，以前的数据也按现在的条件算。</li>
 * </ul>
 *
 * <p><b>怎么算</b>
 * <ul>
 *   <li>访客数和数据概览是同一个算法（{@link TrackConstants#UV_EXPR}，按 {@code distinct_id} 去重），
 *       同一个统计范围两边的访客数对得上。这和漏斗不一样，漏斗会把登录前后的身份合并成一个人。</li>
 *   <li>转化人数：范围内达成过这个目标的访客数，一个访客达成多次只算一个。</li>
 *   <li>转化次数：范围内达成的总次数。</li>
 *   <li>转化率：转化人数 ÷ 范围内的全部访客数，0 到 1，保留 4 位小数；没有访客时是 0。</li>
 *   <li>统计范围和漏斗一样：按租户时区切日，从首日当地 0 点到现在，用服务端接收时间圈范围。</li>
 * </ul>
 *
 * <p><b>为什么直接查明细</b>：目标随时可以加、可以改，没法提前汇总，所以天数限在
 * {@value TrackConstants#GOAL_DAYS_MAX} 天以内、一个应用最多 {@value TrackConstants#GOAL_MAX_PER_APP} 个目标。
 * 一次查询只扫一遍明细：先按访客分组，每个目标一列，记这个访客达成了几次；再数人数、加次数。
 *
 * <p><b>租户隔离</b>：目标跟着应用走，归属租户取应用的归属租户。读写目标由 Flex 租户插件自动带租户条件，
 * 别的租户的目标按编号也查不到；查明细的 SQL 和 {@link TrackFunnelService} 一样先校验应用归属、再显式带租户条件。
 * 事件名、页面地址等所有入参都走占位符，不拼进 SQL。
 *
 * <p><b>缓存</b>：本机缓存 {@value TrackConstants#ANALYSIS_CACHE_SECONDS} 秒，键里带租户和目标定义的指纹，
 * 所以加、改、删目标以后马上按新的定义算，不用等缓存过期，多个节点也不用互相通知。
 *
 * <p><b>纪律</b>：本类在 {@link TrackDS} 范围内，所有数据库访问都落埋点库，不能在这里做权限校验，权限由控制器先校验。
 */
@Service
@TrackDS
public class TrackGoalService {

	/** 拼指纹时字段之间、目标之间的分隔符：用两个控制字符，正常填的名称和地址里不会有 */
	private static final char FIELD_SEP = 31;
	private static final char ROW_SEP = 30;

	private final JdbcTemplate jdbc;
	private final TrackGoalMapper goalMapper;
	private final ObjectProvider<TrackGoalService> self;
	private final TrackTimezoneProperties timezones;

	public TrackGoalService(DataSource dataSource, TrackGoalMapper goalMapper,
							ObjectProvider<TrackGoalService> self, TrackTimezoneProperties timezones) {
		this.jdbc = new JdbcTemplate(dataSource);
		this.goalMapper = goalMapper;
		this.self = self;
		this.timezones = timezones;
	}

	// ==================== 转化查询 ====================

	/**
	 * 查一个应用所有目标的转化情况。
	 *
	 * @param appKey 应用标识，必填
	 * @param days   统计天数，默认 7，只支持 1 到 {@value TrackConstants#GOAL_DAYS_MAX}
	 * @return {@code days}；{@code uv} 统计范围内的全部访客数；
	 *         {@code goals} 每个目标一项 {@code [{id, goalName, goalType, eventName, pagePath, uv, count, rate}]}，
	 *         {@code uv} 转化人数、{@code count} 转化次数、{@code rate} 转化率（0 到 1，4 位小数）；
	 *         按转化人数从多到少排，人数一样的按建目标的先后。还没有目标时 {@code goals} 是空数组
	 */
	public Map<String, Object> conversions(String appKey, Integer days) {
		String key = assertAppKey(appKey);
		int d = assertDays(days);
		String tenant = currentTenant();
		// 这里只为校验应用归属，返回值用不上
		appTenantOf(key, tenant);
		List<TrackGoal> goals = loadGoals(key);
		return self.getObject().cachedConversions(key, d, tenant, timezones.zoneFor(tenant).getId(),
			fingerprint(goals), goals);
	}

	/**
	 * 按目标定义去明细里算。缓存键用目标定义的指纹，不用 {@code goals} 本身；{@code goals} 只在没命中缓存时用来拼查询。
	 */
	@Cached(name = "track:ana:goal:",
		key = "#appKey + ':' + #days + ':' + #tenant + ':' + #zoneId + ':' + #fingerprint",
		expire = TrackConstants.ANALYSIS_CACHE_SECONDS, cacheType = CacheType.LOCAL)
	public Map<String, Object> cachedConversions(String appKey, int days, String tenant, String zoneId,
												 String fingerprint, List<TrackGoal> goals) {
		OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
		OffsetDateTime from = TrackDayWindow.of(ZoneId.of(zoneId), now.toInstant(), days).fromOffset();

		// 里层：一个访客一行，每个目标一列 g0、g1……，是这个访客达成这个目标的次数。占位符按出现的先后填：先是各个目标的条件，再是范围
		List<Object> args = new ArrayList<>();
		StringBuilder inner = new StringBuilder("SELECT e.distinct_id");
		for (int i = 0; i < goals.size(); i++) {
			inner.append(", count(*) FILTER (WHERE ").append(condition(goals.get(i), args)).append(") AS g").append(i);
		}
		inner.append(" FROM track_event e WHERE e.app_key = ? AND e.received_at >= ? AND e.received_at <= ?");
		args.add(appKey);
		args.add(from);
		args.add(now);
		inner.append(tenantFrag("e", tenant, args));
		inner.append(" GROUP BY e.distinct_id");

		// 外层：行数就是访客数；某一列大于 0 的行数是转化人数，这一列的合计是转化次数
		StringBuilder sql = new StringBuilder("SELECT count(u.distinct_id) AS uv");
		for (int i = 0; i < goals.size(); i++) {
			sql.append(", count(u.distinct_id) FILTER (WHERE u.g").append(i).append(" > 0) AS u").append(i);
			sql.append(", coalesce(sum(u.g").append(i).append("), 0) AS c").append(i);
		}
		sql.append(" FROM (").append(inner).append(") u");
		Map<String, Object> row = jdbc.queryForMap(sql.toString(), args.toArray());

		long uv = toLong(row.get("uv"));
		List<Map<String, Object>> list = new ArrayList<>(goals.size());
		for (int i = 0; i < goals.size(); i++) {
			TrackGoal goal = goals.get(i);
			long goalUv = toLong(row.get("u" + i));
			Map<String, Object> item = new LinkedHashMap<>();
			// 编号按字符串给：雪花编号超过了浏览器里数字的精度
			item.put("id", String.valueOf(goal.getId()));
			item.put("goalName", goal.getGoalName());
			item.put("goalType", goal.getGoalType());
			item.put("eventName", goal.getEventName());
			item.put("pagePath", goal.getPagePath());
			item.put("uv", goalUv);
			item.put("count", toLong(row.get("c" + i)));
			item.put("rate", ratio(goalUv, uv));
			list.add(item);
		}
		// 排序是稳定的：目标本来按编号（建的先后）排好了，转化人数一样的保持这个次序
		list.sort(Comparator.comparingLong((Map<String, Object> item) -> (Long) item.get("uv")).reversed());

		Map<String, Object> result = new LinkedHashMap<>();
		result.put("days", days);
		result.put("uv", uv);
		result.put("goals", list);
		return result;
	}

	/**
	 * 一个目标的达成条件，写成对 {@code track_event e} 的一段判断，值追加到入参里。
	 * 定义不完整（保存时校验过，正常不会有）按永远不达成算，不让整个查询失败。
	 */
	private static String condition(TrackGoal goal, List<Object> args) {
		if (TrackConstants.GOAL_TYPE_EVENT.equals(goal.getGoalType())) {
			if (goal.getEventName() == null) {
				return "false";
			}
			args.add(goal.getEventName());
			return "e.event_name = ?";
		}
		String path = goal.getPagePath();
		if (path == null || !TrackConstants.GOAL_TYPE_PAGE.equals(goal.getGoalType())) {
			return "false";
		}
		args.add(TrackConstants.EVENT_PAGEVIEW);
		if (path.endsWith(TrackConstants.GOAL_PAGE_WILDCARD)) {
			String prefix = path.substring(0, path.length() - TrackConstants.GOAL_PAGE_WILDCARD.length());
			args.add(prefix);
			args.add(prefix);
			return "e.event_name = ? AND (starts_with(e.route_path, ?) OR starts_with(e.url_path, ?))";
		}
		args.add(path);
		args.add(path);
		return "e.event_name = ? AND (e.route_path = ? OR e.url_path = ?)";
	}

	/** 目标定义的指纹，当缓存键的一部分：加、改、删任何一个目标，指纹都会变，旧的缓存自然用不上 */
	private static String fingerprint(List<TrackGoal> goals) {
		StringBuilder text = new StringBuilder();
		for (TrackGoal goal : goals) {
			text.append(goal.getId()).append(FIELD_SEP).append(goal.getGoalName()).append(FIELD_SEP)
				.append(goal.getGoalType()).append(FIELD_SEP).append(goal.getEventName()).append(FIELD_SEP)
				.append(goal.getPagePath()).append(ROW_SEP);
		}
		try {
			byte[] digest = MessageDigest.getInstance("SHA-256").digest(text.toString().getBytes(StandardCharsets.UTF_8));
			return HexFormat.of().formatHex(digest, 0, 16);
		} catch (NoSuchAlgorithmException e) {
			throw new IllegalStateException(e);
		}
	}

	// ==================== 目标的新增、编辑、删除 ====================

	/**
	 * 新增或编辑目标：没有编号是新增，有编号是编辑。
	 *
	 * <p>新增时应用、名称、类型和对应的条件都要填。编辑时应用不能改（传了也不看），其余字段没传的保持原样；
	 * 改了类型就要把新类型的条件一起传来，另一种类型的条件会清掉。归属租户和操作人由服务端定，不接受提交。
	 *
	 * @return 给用户看的一句话
	 */
	public String submit(Long id, String appKey, String goalName, String goalType, String eventName,
						 String pagePath, long userId) {
		if (id == null) {
			return create(appKey, goalName, goalType, eventName, pagePath, userId);
		}
		return update(id, goalName, goalType, eventName, pagePath, userId);
	}

	private String create(String appKey, String goalName, String goalType, String eventName, String pagePath,
						  long userId) {
		String key = assertAppKey(appKey);
		String appTenant = appTenantOf(key, currentTenant());
		String name = requireGoalName(goalName);
		String type = requireGoalType(goalType);
		boolean byEvent = TrackConstants.GOAL_TYPE_EVENT.equals(type);
		String event = byEvent ? requireEventName(eventName) : null;
		String page = byEvent ? null : requirePagePath(pagePath);

		long existing = goalMapper.selectCountByQuery(QueryWrapper.create().where("app_key = ?", key));
		if (existing >= TrackConstants.GOAL_MAX_PER_APP) {
			throw new ServiceException(I18n.get("一个应用最多 {0} 个目标。请先删掉不用的再新增", TrackConstants.GOAL_MAX_PER_APP));
		}
		assertNameFree(key, name, null);

		TrackGoal goal = new TrackGoal();
		goal.setAppKey(key);
		goal.setGoalName(name);
		goal.setGoalType(type);
		goal.setEventName(event);
		goal.setPagePath(page);
		// 目标跟着应用走：超级管理员替别的租户的应用建目标，那个租户自己也要看得到
		goal.setTenantId(appTenant);
		goal.setCreateBy(userId);
		goal.sanitizeForInsert();
		try {
			goalMapper.insertSelective(goal);
		} catch (DuplicateKeyException e) {
			// 两个人同时建同名目标：后一个撞唯一索引，给同一句提示
			throw new ServiceException("已经有同名的目标了。请换一个名称");
		}
		return I18n.get("已保存目标「{0}」。以前的数据也按这个条件算", name);
	}

	private String update(long id, String goalName, String goalType, String eventName, String pagePath, long userId) {
		TrackGoal goal = goalMapper.selectOneByQuery(QueryWrapper.create().where("id = ?", id));
		if (goal == null) {
			throw new ServiceException("这个目标不存在。请刷新列表后再试");
		}
		String name = goalName == null ? goal.getGoalName() : requireGoalName(goalName);
		String type = goalType == null ? goal.getGoalType() : requireGoalType(goalType);
		boolean byEvent = TrackConstants.GOAL_TYPE_EVENT.equals(type);
		String event = byEvent ? requireEventName(eventName == null ? goal.getEventName() : eventName) : null;
		String page = byEvent ? null : requirePagePath(pagePath == null ? goal.getPagePath() : pagePath);
		if (!name.equals(goal.getGoalName())) {
			assertNameFree(goal.getAppKey(), name, goal.getId());
		}
		// 显式列更新：换了类型要把另一种类型的条件写成 NULL，按实体更新会跳过 null 列
		try {
			UpdateChain.of(TrackGoal.class)
				.set("goal_name", name)
				.set("goal_type", type)
				.set("event_name", event)
				.set("page_path", page)
				.set("update_by", userId)
				.setRaw("update_time", "now()")
				.where("id = ?", goal.getId())
				.update();
		} catch (DuplicateKeyException e) {
			throw new ServiceException("已经有同名的目标了。请换一个名称");
		}
		return I18n.get("已保存目标「{0}」。以前的数据也按这个条件算", name);
	}

	/**
	 * 删除目标（逻辑删除）。删掉的只是这条定义，事件明细不动；以后用同一个名称可以重新建。
	 *
	 * @return 给用户看的一句话
	 */
	public String remove(long id) {
		TrackGoal goal = goalMapper.selectOneByQuery(QueryWrapper.create().where("id = ?", id));
		if (goal == null) {
			throw new ServiceException("这个目标不存在。请刷新列表后再试");
		}
		goalMapper.deleteByQuery(QueryWrapper.create().where("id = ?", id));
		return I18n.get("已删除目标「{0}」。已经上报的事件不受影响", goal.getGoalName());
	}

	// ==================== 入参校验 ====================

	/** 一个应用的全部目标，按编号（建的先后）排；租户条件由 Flex 插件自动带上 */
	private List<TrackGoal> loadGoals(String appKey) {
		return goalMapper.selectListByQuery(QueryWrapper.create().where("app_key = ?", appKey).orderBy("id", true));
	}

	/** 同一个应用里名称不能重复；{@code selfId} 是正在编辑的那个目标，和自己同名不算 */
	private void assertNameFree(String appKey, String name, Long selfId) {
		TrackGoal same = goalMapper.selectOneByQuery(
			QueryWrapper.create().where("app_key = ?", appKey).and("goal_name = ?", name));
		if (same != null && !same.getId().equals(selfId)) {
			throw new ServiceException("已经有同名的目标了。请换一个名称");
		}
	}

	private String requireGoalName(String goalName) {
		if (goalName == null || goalName.isBlank()) {
			throw new ServiceException("请填写目标名称");
		}
		String name = goalName.trim();
		if (name.length() > TrackConstants.GOAL_NAME_MAX_LEN) {
			throw new ServiceException(I18n.get("目标名称请控制在 {0} 个字以内", TrackConstants.GOAL_NAME_MAX_LEN));
		}
		return name;
	}

	private String requireGoalType(String goalType) {
		String type = goalType == null ? "" : goalType.trim();
		if (TrackConstants.GOAL_TYPE_EVENT.equals(type) || TrackConstants.GOAL_TYPE_PAGE.equals(type)) {
			return type;
		}
		throw new ServiceException("目标类型只支持触发事件和访问页面。请重新选择");
	}

	private String requireEventName(String eventName) {
		if (eventName == null || eventName.isBlank()) {
			throw new ServiceException("请填写事件名。触发这个事件就算达成目标");
		}
		String name = eventName.trim();
		if (!TrackConstants.CUSTOM_EVENT_NAME.matcher(name).matches()) {
			throw new ServiceException("事件名请用字母开头，后面只能是字母、数字或下划线，最长 64 位");
		}
		return name;
	}

	/** 页面地址：以 / 开头；* 只能有一个，而且只能在末尾 */
	private String requirePagePath(String pagePath) {
		if (pagePath == null || pagePath.isBlank()) {
			throw new ServiceException("请填写页面地址。访问这个页面就算达成目标");
		}
		String path = pagePath.trim();
		if (!path.startsWith("/")) {
			throw new ServiceException("页面地址请以 / 开头，例如 /order/success");
		}
		if (path.length() > TrackConstants.DIM_MAX_LEN) {
			throw new ServiceException(I18n.get("页面地址请控制在 {0} 个字以内", TrackConstants.DIM_MAX_LEN));
		}
		int star = path.indexOf(TrackConstants.GOAL_PAGE_WILDCARD);
		if (star >= 0 && star != path.length() - TrackConstants.GOAL_PAGE_WILDCARD.length()) {
			throw new ServiceException("页面地址里的 * 只能放在末尾，表示这个地址开头的所有页面。请改完再保存");
		}
		return path;
	}

	/** 统计天数：缺省 7 天；须在 1..{@value TrackConstants#GOAL_DAYS_MAX}，否则说明重新选择 */
	private int assertDays(Integer days) {
		if (days == null) {
			return 7;
		}
		if (days < 1 || days > TrackConstants.GOAL_DAYS_MAX) {
			throw new ServiceException(I18n.get("统计天数只支持 1 到 {0} 天。请从今天、近 7 天或近 30 天里重新选择", TrackConstants.GOAL_DAYS_MAX));
		}
		return days;
	}

	/** 比率：0 到 1，保留 4 位小数；分母是 0 时返回 0。和页面排行里的跳出率、退出率是同一个算法 */
	private static double ratio(long part, long whole) {
		return whole == 0 ? 0.0 : Math.round(part * 10000.0 / whole) / 10000.0;
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

	/**
	 * 应用必须是当前租户的，返回它归属的租户。查不到就报错，不返回一份全是 0 的结果让人误以为没有数据。
	 */
	private String appTenantOf(String appKey, String tenant) {
		List<Object> args = new ArrayList<>(List.of(appKey));
		String sql = "SELECT tenant_id FROM track_app WHERE app_key = ? AND is_deleted = 0" + tenantFrag(null, tenant, args);
		List<String> found = jdbc.queryForList(sql, String.class, args.toArray());
		if (found.isEmpty()) {
			throw new ServiceException("应用不存在或无权访问。请刷新应用列表后再看；如果这是别的租户的应用，请用那个租户的账号打开");
		}
		return found.get(0);
	}

	private String assertAppKey(String appKey) {
		if (appKey == null || appKey.isBlank()) {
			throw new ServiceException("请先选择应用。转化目标是按应用分开的");
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
