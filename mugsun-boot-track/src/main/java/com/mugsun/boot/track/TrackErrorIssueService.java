package com.mugsun.boot.track;

import cn.hutool.core.util.IdUtil;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mugsun.boot.common.constant.TrackConstants;
import com.mugsun.boot.tenant.TenantContext;
import com.mugsun.boot.track.entity.TrackErrorIssue;
import com.mugsun.boot.track.mapper.TrackErrorIssueMapper;
import com.mugsun.core.tool.exception.ServiceException;
import com.mybatisflex.core.paginate.Page;
import com.mybatisflex.core.query.QueryWrapper;
import com.mybatisflex.core.update.UpdateChain;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import javax.sql.DataSource;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 错误问题单业务：摄入侧 upsert（静默）+ 管理端列表/详情/指派/解决/重开。
 * <p>读权复用 {@link TrackConstants#PERM_ERROR_LIST}；写权
 * {@link TrackConstants#PERM_ERROR_ISSUE_EDIT}（控制器注解）。
 * <p><b>数据源</b>：类级 {@code @TrackDS}，严禁调用业务库 DAO / Sa-Token。
 * 摄入路径无请求租户上下文，upsert 用事件自带 tenant_id 写原生 SQL（不依赖租户插件）。
 * <p>RESOLVED 后再指派或自动复发：均 reopen 并清 resolvedAt；自动复发额外 regressed=true。
 */
@Service
@TrackDS
public class TrackErrorIssueService {

	private static final Logger log = LoggerFactory.getLogger(TrackErrorIssueService.class);
	private static final ObjectMapper PLAIN = new ObjectMapper();

	private final TrackErrorIssueMapper issueMapper;
	private final JdbcTemplate jdbc;

	public TrackErrorIssueService(TrackErrorIssueMapper issueMapper, DataSource dataSource) {
		this.issueMapper = issueMapper;
		this.jdbc = new JdbcTemplate(dataSource);
	}

	/** 消费侧织入：非阻塞静默——任何异常只记日志，不影响落库主流程 */
	public void upsertQuietly(TrackIngestEvent event) {
		try {
			upsert(event);
		} catch (Exception e) {
			log.warn("错误问题单 upsert 失败（静默）app={} fp={}：{}",
				event.getAppKey(), event.getErrorFingerprint(), e.getMessage());
		}
	}

	/**
	 * 按 appKey+fingerprint upsert：新建 OPEN；非 RESOLVED 累加刷新；
	 * RESOLVED 再来 → regressed=true、reopen（有指派→ASSIGNED 否则 OPEN）、清 resolvedAt。
	 */
	void upsert(TrackIngestEvent event) {
		String fingerprint = event.getErrorFingerprint();
		if (fingerprint == null || fingerprint.isBlank()) {
			return;
		}
		if (event.getAppKey() == null || event.getAppKey().isBlank()) {
			return;
		}
		String tenantId = event.getTenantId();
		if (tenantId == null || tenantId.isBlank()) {
			log.warn("错误问题单跳过：事件无 tenantId app={} fp={}", event.getAppKey(), fingerprint);
			return;
		}
		PropsBits props = PropsBits.from(event.getPropsJson());
		LocalDateTime seenAt = LocalDateTime.ofInstant(
			Instant.ofEpochMilli(event.getReceivedAtMs() > 0 ? event.getReceivedAtMs() : event.getTsMs()),
			ZoneOffset.UTC);
		String release = truncate(props.release, TrackConstants.SOURCEMAP_RELEASE_MAX_LEN);
		String title = truncate(props.message, TrackConstants.ERROR_ISSUE_TITLE_MAX_LEN);
		String stack = truncate(props.stack, TrackConstants.PROPS_STACK_MAX_LEN);
		String releasesJson = release == null ? "[]" : "[\"" + jsonEscape(release) + "\"]";
		long id = IdUtil.getSnowflakeNextId();
		int sessionInc = event.getSessionId() == null || event.getSessionId().isBlank() ? 0 : 1;
		int userInc = event.getDistinctId() == null || event.getDistinctId().isBlank() ? 0 : 1;

		// 原子 upsert：RESOLVED 再来 → regressed=1、清 resolved_at、按是否有指派 reopen
		String sql = "INSERT INTO track_error_issue ("
			+ "id, app_key, fingerprint, title, status, first_release, last_release,"
			+ " first_seen_at, last_seen_at, event_count, session_count, user_count, regressed,"
			+ " latest_session_id, latest_user_id, latest_distinct_id, latest_event_id,"
			+ " latest_url_path, latest_route_path, latest_stack, releases, tenant_id,"
			+ " create_time, update_time, is_deleted)"
			+ " VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,now(),now(),0)"
			+ " ON CONFLICT (app_key, fingerprint) WHERE is_deleted = 0 DO UPDATE SET"
			+ " title = COALESCE(EXCLUDED.title, track_error_issue.title),"
			+ " last_release = COALESCE(EXCLUDED.last_release, track_error_issue.last_release),"
			+ " last_seen_at = EXCLUDED.last_seen_at,"
			+ " event_count = track_error_issue.event_count + 1,"
			+ " session_count = track_error_issue.session_count"
			+ " + CASE WHEN EXCLUDED.latest_session_id IS NOT NULL"
			+ " AND EXCLUDED.latest_session_id IS DISTINCT FROM track_error_issue.latest_session_id"
			+ " THEN 1 ELSE 0 END,"
			+ " user_count = track_error_issue.user_count"
			+ " + CASE WHEN EXCLUDED.latest_distinct_id IS NOT NULL"
			+ " AND EXCLUDED.latest_distinct_id IS DISTINCT FROM track_error_issue.latest_distinct_id"
			+ " THEN 1 ELSE 0 END,"
			+ " latest_session_id = COALESCE(EXCLUDED.latest_session_id, track_error_issue.latest_session_id),"
			+ " latest_user_id = COALESCE(EXCLUDED.latest_user_id, track_error_issue.latest_user_id),"
			+ " latest_distinct_id = COALESCE(EXCLUDED.latest_distinct_id, track_error_issue.latest_distinct_id),"
			+ " latest_event_id = COALESCE(EXCLUDED.latest_event_id, track_error_issue.latest_event_id),"
			+ " latest_url_path = COALESCE(EXCLUDED.latest_url_path, track_error_issue.latest_url_path),"
			+ " latest_route_path = COALESCE(EXCLUDED.latest_route_path, track_error_issue.latest_route_path),"
			+ " latest_stack = COALESCE(EXCLUDED.latest_stack, track_error_issue.latest_stack),"
			+ " releases = CASE"
			+ " WHEN EXCLUDED.last_release IS NULL THEN track_error_issue.releases"
			+ " WHEN position('\"' || EXCLUDED.last_release || '\"' in COALESCE(track_error_issue.releases, '[]')) > 0"
			+ " THEN track_error_issue.releases"
			+ " WHEN COALESCE(track_error_issue.releases, '[]') IN ('[]', '') THEN EXCLUDED.releases"
			+ " ELSE left(COALESCE(track_error_issue.releases, '[]'),"
			+ " length(COALESCE(track_error_issue.releases, '[]')) - 1)"
			+ " || ',\"' || EXCLUDED.last_release || '\"]' END,"
			+ " status = CASE WHEN track_error_issue.status = 'RESOLVED' THEN"
			+ " CASE WHEN track_error_issue.assignee_id IS NOT NULL THEN 'ASSIGNED' ELSE 'OPEN' END"
			+ " ELSE track_error_issue.status END,"
			+ " resolved_at = CASE WHEN track_error_issue.status = 'RESOLVED' THEN NULL"
			+ " ELSE track_error_issue.resolved_at END,"
			+ " regressed = CASE WHEN track_error_issue.status = 'RESOLVED' THEN 1"
			+ " ELSE track_error_issue.regressed END,"
			+ " update_time = now()";

		jdbc.update(sql,
			id,
			event.getAppKey(),
			fingerprint,
			title,
			TrackConstants.ERROR_ISSUE_STATUS_OPEN,
			release,
			release,
			Timestamp.valueOf(seenAt),
			Timestamp.valueOf(seenAt),
			1L,
			(long) sessionInc,
			(long) userInc,
			0,
			event.getSessionId(),
			event.getUserId(),
			event.getDistinctId(),
			event.getEventId(),
			truncate(event.getUrlPath(), TrackConstants.URL_MAX_LEN),
			truncate(event.getRoutePath(), TrackConstants.DIM_MAX_LEN),
			stack,
			releasesJson,
			tenantId);
	}

	/** 列表分页；筛了 release 时附带 releaseFlag（NEW/REGRESSED/ONGOING） */
	public Page<Map<String, Object>> page(String appKey, String release, String status, Long assigneeId,
										 Boolean regressedOnly, long pageNum, long pageSize) {
		String tenant = currentTenant();
		if (appKey != null && !appKey.isBlank()) {
			assertAppVisible(appKey.trim(), tenant);
		}
		long size = clampPageSize(pageSize);
		long page = pageNum < 1 ? 1 : pageNum;
		StringBuilder cond = new StringBuilder(" FROM track_error_issue WHERE is_deleted = 0");
		List<Object> args = new ArrayList<>();
		if (appKey != null && !appKey.isBlank()) {
			cond.append(" AND app_key = ?");
			args.add(appKey.trim());
		}
		if (status != null && !status.isBlank()) {
			String st = status.trim().toUpperCase();
			assertStatus(st);
			cond.append(" AND status = ?");
			args.add(st);
		}
		if (assigneeId != null) {
			cond.append(" AND assignee_id = ?");
			args.add(assigneeId);
		}
		if (Boolean.TRUE.equals(regressedOnly)) {
			cond.append(" AND regressed = 1");
		}
		String releaseFilter = null;
		if (release != null && !release.isBlank()) {
			releaseFilter = release.trim();
			cond.append(" AND (first_release = ? OR last_release = ? OR position(? in coalesce(releases, '')) > 0)");
			args.add(releaseFilter);
			args.add(releaseFilter);
			args.add("\"" + releaseFilter + "\"");
		}
		cond.append(tenantFrag(null, tenant, args));

		Long total = jdbc.queryForObject("SELECT count(*)" + cond, Long.class, args.toArray());
		List<Object> pageArgs = new ArrayList<>(args);
		String sql = "SELECT id, app_key AS \"appKey\", fingerprint, title, status,"
			+ " assignee_id AS \"assigneeId\", first_release AS \"firstRelease\","
			+ " last_release AS \"lastRelease\","
			+ " CAST(EXTRACT(EPOCH FROM first_seen_at) * 1000 AS BIGINT) AS \"firstSeenAt\","
			+ " CAST(EXTRACT(EPOCH FROM last_seen_at) * 1000 AS BIGINT) AS \"lastSeenAt\","
			+ " CAST(EXTRACT(EPOCH FROM resolved_at) * 1000 AS BIGINT) AS \"resolvedAt\","
			+ " event_count AS \"eventCount\", session_count AS \"sessionCount\","
			+ " user_count AS \"userCount\", regressed,"
			+ " CAST(EXTRACT(EPOCH FROM update_time) * 1000 AS BIGINT) AS \"updateTime\""
			+ cond + " ORDER BY last_seen_at DESC, id DESC LIMIT ? OFFSET ?";
		pageArgs.add(size);
		pageArgs.add((page - 1) * size);
		List<Map<String, Object>> records = jdbc.queryForList(sql, pageArgs.toArray());
		for (Map<String, Object> row : records) {
			normalizeFlags(row, releaseFilter);
		}
		return new Page<>(records, page, size, total == null ? 0L : total);
	}

	/** 详情：id 优先；否则 appKey+fingerprint */
	public Map<String, Object> detail(Long id, String appKey, String fingerprint) {
		String tenant = currentTenant();
		TrackErrorIssue issue = loadIssue(id, appKey, fingerprint, tenant);
		Map<String, Object> row = toDetailMap(issue);
		if (issue.getReleases() != null && !issue.getReleases().isBlank()) {
			try {
				row.put("recentReleases", PLAIN.readValue(issue.getReleases(), List.class));
			} catch (Exception e) {
				row.put("recentReleases", List.of());
			}
		} else {
			row.put("recentReleases", List.of());
		}
		return row;
	}

	/**
	 * 指派：assigneeId 空=取消指派；RESOLVED 再指派 → reopen（有人→ASSIGNED / 取消→OPEN）并清 resolvedAt。
	 */
	public Map<String, Object> assign(Long id, String appKey, Long assigneeId) {
		String tenant = currentTenant();
		TrackErrorIssue issue = loadIssue(id, appKey, null, tenant);
		boolean wasResolved = TrackConstants.ERROR_ISSUE_STATUS_RESOLVED.equals(issue.getStatus());
		String status;
		if (assigneeId == null) {
			status = TrackConstants.ERROR_ISSUE_STATUS_OPEN;
		} else {
			status = TrackConstants.ERROR_ISSUE_STATUS_ASSIGNED;
		}
		// UpdateChain 显式列：assignee_id/resolved_at 清空需写 NULL（update(ignoreNulls) 会跳过）
		UpdateChain.of(TrackErrorIssue.class)
			.set("status", status)
			.set("assignee_id", assigneeId)
			.set("resolved_at", wasResolved ? null : issue.getResolvedAt())
			.setRaw("update_time", "now()")
			.where("id = ?", issue.getId())
			.update();
		issue.setStatus(status);
		issue.setAssigneeId(assigneeId);
		if (wasResolved) {
			issue.setResolvedAt(null);
		}
		return toDetailMap(issue);
	}

	/** 标记已解决 */
	public Map<String, Object> resolve(Long id, String appKey) {
		String tenant = currentTenant();
		TrackErrorIssue issue = loadIssue(id, appKey, null, tenant);
		LocalDateTime resolvedAt = LocalDateTime.now(ZoneOffset.UTC);
		UpdateChain.of(TrackErrorIssue.class)
			.set("status", TrackConstants.ERROR_ISSUE_STATUS_RESOLVED)
			.set("resolved_at", resolvedAt)
			.setRaw("update_time", "now()")
			.where("id = ?", issue.getId())
			.update();
		issue.setStatus(TrackConstants.ERROR_ISSUE_STATUS_RESOLVED);
		issue.setResolvedAt(resolvedAt);
		return toDetailMap(issue);
	}

	/** 重开：有指派→ASSIGNED，否则 OPEN；清 resolvedAt（不自动清 regressed） */
	public Map<String, Object> reopen(Long id, String appKey) {
		String tenant = currentTenant();
		TrackErrorIssue issue = loadIssue(id, appKey, null, tenant);
		String status = issue.getAssigneeId() != null
			? TrackConstants.ERROR_ISSUE_STATUS_ASSIGNED
			: TrackConstants.ERROR_ISSUE_STATUS_OPEN;
		UpdateChain.of(TrackErrorIssue.class)
			.set("status", status)
			.set("resolved_at", null)
			.setRaw("update_time", "now()")
			.where("id = ?", issue.getId())
			.update();
		issue.setStatus(status);
		issue.setResolvedAt(null);
		return toDetailMap(issue);
	}

	/**
	 * 按 id 读写必须同时带 appKey（防仅凭 id 的 IDOR）；再校验租户与 appKey 归属。
	 * 查不到 / 跨租户 / 跨 app 一律「不存在或无权」，不泄露他租户存在性。
	 */
	private TrackErrorIssue loadIssue(Long id, String appKey, String fingerprint, String tenant) {
		TrackErrorIssue issue = null;
		String key = appKey == null ? null : appKey.trim();
		if (id != null) {
			if (key == null || key.isEmpty()) {
				throw new ServiceException("请同时传入问题单 id 与 appKey");
			}
			issue = issueMapper.selectOneById(id);
		} else if (key != null && !key.isEmpty() && fingerprint != null && !fingerprint.isBlank()) {
			issue = findByNaturalKey(key, fingerprint.trim());
		} else {
			throw new ServiceException("请传入问题单 id 与 appKey，或同时传入 appKey 与 fingerprint");
		}
		if (issue == null || (issue.getIsDeleted() != null && issue.getIsDeleted() == 1)) {
			throw new ServiceException("问题单不存在或无权访问。请刷新列表后再看");
		}
		if (tenant != null && !tenant.equals(issue.getTenantId())) {
			throw new ServiceException("问题单不存在或无权访问。请刷新列表后再看");
		}
		if (key != null && !key.isEmpty() && !key.equals(issue.getAppKey())) {
			throw new ServiceException("问题单不存在或无权访问。请刷新列表后再看");
		}
		return issue;
	}

	private TrackErrorIssue findByNaturalKey(String appKey, String fingerprint) {
		return issueMapper.selectOneByQuery(QueryWrapper.create()
			.eq("app_key", appKey)
			.eq("fingerprint", fingerprint));
	}

	private Map<String, Object> toDetailMap(TrackErrorIssue issue) {
		Map<String, Object> row = new LinkedHashMap<>();
		row.put("id", issue.getId());
		row.put("appKey", issue.getAppKey());
		row.put("fingerprint", issue.getFingerprint());
		row.put("title", issue.getTitle());
		row.put("status", issue.getStatus());
		row.put("assigneeId", issue.getAssigneeId());
		row.put("firstRelease", issue.getFirstRelease());
		row.put("lastRelease", issue.getLastRelease());
		row.put("firstSeenAt", epochMs(issue.getFirstSeenAt()));
		row.put("lastSeenAt", epochMs(issue.getLastSeenAt()));
		row.put("resolvedAt", epochMs(issue.getResolvedAt()));
		row.put("eventCount", issue.getEventCount());
		row.put("sessionCount", issue.getSessionCount());
		row.put("userCount", issue.getUserCount());
		row.put("regressed", issue.getRegressed() != null && issue.getRegressed() == 1);
		row.put("updateTime", epochMs(issue.getUpdateTime()));
		row.put("latestSessionId", issue.getLatestSessionId());
		row.put("latestUserId", issue.getLatestUserId());
		row.put("latestDistinctId", issue.getLatestDistinctId());
		row.put("latestEventId", issue.getLatestEventId());
		row.put("latestUrlPath", issue.getLatestUrlPath());
		row.put("latestRoutePath", issue.getLatestRoutePath());
		row.put("latestStack", issue.getLatestStack());
		return row;
	}

	private void normalizeFlags(Map<String, Object> row, String releaseFilter) {
		Object reg = row.get("regressed");
		boolean regressed = reg instanceof Number ? ((Number) reg).intValue() == 1
			: Boolean.TRUE.equals(reg);
		row.put("regressed", regressed);
		if (releaseFilter == null) {
			row.remove("releaseFlag");
			return;
		}
		String first = textOf(row.get("firstRelease"));
		String last = textOf(row.get("lastRelease"));
		if (releaseFilter.equals(first)) {
			row.put("releaseFlag", TrackConstants.ERROR_ISSUE_FLAG_NEW);
		} else if (regressed && releaseFilter.equals(last)) {
			row.put("releaseFlag", TrackConstants.ERROR_ISSUE_FLAG_REGRESSED);
		} else {
			row.put("releaseFlag", TrackConstants.ERROR_ISSUE_FLAG_ONGOING);
		}
	}

	private void assertStatus(String status) {
		if (!TrackConstants.ERROR_ISSUE_STATUS_OPEN.equals(status)
			&& !TrackConstants.ERROR_ISSUE_STATUS_ASSIGNED.equals(status)
			&& !TrackConstants.ERROR_ISSUE_STATUS_RESOLVED.equals(status)) {
			throw new ServiceException("状态只支持 OPEN、ASSIGNED 或 RESOLVED");
		}
	}

	private String currentTenant() {
		Object[] ids = TenantContext.resolveTenantIds();
		return ids == null ? null : String.valueOf(ids[0]);
	}

	/** 应用可见性：0 行 = 不存在或他租户（fail-closed，与 funnel/retention 同口径） */
	private void assertAppVisible(String appKey, String tenant) {
		StringBuilder sql = new StringBuilder(
			"SELECT count(*) FROM track_app WHERE app_key = ? AND is_deleted = 0");
		List<Object> args = new ArrayList<>();
		args.add(appKey);
		sql.append(tenantFrag(null, tenant, args));
		Long c = jdbc.queryForObject(sql.toString(), Long.class, args.toArray());
		if (c == null || c == 0L) {
			throw new ServiceException("应用不存在或无权访问。请刷新应用列表后再看；如果这是别的租户的应用，请用那个租户的账号打开");
		}
	}

	private String tenantFrag(String alias, String tenant, List<Object> args) {
		if (tenant == null) {
			return "";
		}
		String col = alias == null || alias.isBlank() ? "tenant_id" : alias + ".tenant_id";
		args.add(tenant);
		return " AND " + col + " = ?";
	}

	private long clampPageSize(long pageSize) {
		if (pageSize < 1) {
			return 10;
		}
		return Math.min(pageSize, TrackConstants.QUERY_PAGE_SIZE_MAX);
	}

	private static Long epochMs(LocalDateTime t) {
		if (t == null) {
			return null;
		}
		return t.toInstant(ZoneOffset.UTC).toEpochMilli();
	}

	private static String textOf(Object value) {
		return value == null ? null : String.valueOf(value);
	}

	private static String truncate(String s, int max) {
		return TrackPropsText.truncate(s, max);
	}

	private static String jsonEscape(String s) {
		return s.replace("\\", "\\\\").replace("\"", "\\\"");
	}

	private record PropsBits(String message, String release, String stack) {
		static PropsBits from(String propsJson) {
			if (propsJson == null || propsJson.isBlank()) {
				return new PropsBits(null, null, null);
			}
			try {
				JsonNode node = PLAIN.readTree(propsJson);
				return new PropsBits(
					text(node, TrackConstants.PROP_ERROR_MESSAGE),
					text(node, TrackConstants.PROP_RELEASE),
					text(node, TrackConstants.PROP_ERROR_STACK));
			} catch (Exception e) {
				return new PropsBits(null, null, null);
			}
		}

		private static String text(JsonNode node, String field) {
			if (node == null) {
				return null;
			}
			JsonNode v = node.get(field);
			if (v == null || v.isNull() || v.isContainerNode()) {
				return null;
			}
			String t = v.asText();
			return t == null || t.isBlank() ? null : t;
		}
	}
}
