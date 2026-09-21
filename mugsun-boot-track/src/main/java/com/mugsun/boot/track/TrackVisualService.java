package com.mugsun.boot.track;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mugsun.boot.common.constant.TrackConstants;
import com.mugsun.boot.tenant.TenantContext;
import com.mugsun.boot.track.entity.TrackEventDef;
import com.mugsun.boot.track.entity.TrackVisualRule;
import com.mugsun.boot.track.mapper.TrackEventDefMapper;
import com.mugsun.boot.track.mapper.TrackVisualRuleMapper;
import com.mugsun.core.tool.exception.ServiceException;
import com.mybatisflex.core.paginate.Page;
import com.mybatisflex.core.query.QueryWrapper;
import com.mybatisflex.core.update.UpdateChain;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import javax.sql.DataSource;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * 圈选式可视化埋点业务（G104）：圈选令牌签发 / 匿名草稿上报 / 管理端草稿确认与丢弃 / 规则 CRUD。
 * <p>令牌即授权：48 位随机 hex，Redis HASH {@code mugsun:track:visual-token:{token}} 存 {appKey,tenantId,userId}，
 * TTL {@value TrackConstants#VISUAL_TOKEN_TTL_SECONDS}s（草稿列表键随其续期）；草稿右推 Redis list
 * {@code mugsun:track:visual-draft:{token}}（不落事件表、不进实时流，防采样丢失+防污染统计）。
 * <p>管理端令牌操作一律校验 HASH.tenantId 与当前租户一致（当前租户 null=超管放行，照
 * {@link TenantContext#resolveTenantIds} 口径）；确认成规则走自然键「查-改或插」（重复圈选 = 更新而非堆行，
 * 并发撞 uk_visual_rule 唯一索引转友好 400）。
 * <p><b>分层纪律</b>：权限校验（Sa-Token）留控制器；本类 DB 读写全部落埋点库（@TrackDS），
 * 确认/编辑/删除后主动失效 {@link TrackVisualRuleService} 配置缓存与 {@link TrackEventDefService} 停用判定缓存。
 */
@Service
@TrackDS
public class TrackVisualService {

	private static final Logger log = LoggerFactory.getLogger(TrackVisualService.class);
	/** 草稿元素 JSON 的原始解析器（与采集端同因：规避 XSS 净化反序列化器对 selector 文本的改写） */
	private static final ObjectMapper PLAIN_MAPPER = new ObjectMapper();
	/** 令牌随机源（48 位 hex = 192bit 熵） */
	private static final SecureRandom RANDOM = new SecureRandom();
	private static final DateTimeFormatter MINUTE_FORMAT = DateTimeFormatter.ofPattern(TrackConstants.RATE_LIMIT_MINUTE_PATTERN);

	private final TrackVisualRuleMapper visualRuleMapper;
	private final TrackEventDefMapper eventDefMapper;
	private final TrackVisualRuleService visualRuleService;
	private final TrackEventDefService eventDefService;
	private final StringRedisTemplate redis;
	private final JdbcTemplate jdbc;

	public TrackVisualService(TrackVisualRuleMapper visualRuleMapper, TrackEventDefMapper eventDefMapper,
							  TrackVisualRuleService visualRuleService, TrackEventDefService eventDefService,
							  StringRedisTemplate redis, DataSource dataSource) {
		this.visualRuleMapper = visualRuleMapper;
		this.eventDefMapper = eventDefMapper;
		this.visualRuleService = visualRuleService;
		this.eventDefService = eventDefService;
		this.redis = redis;
		this.jdbc = new JdbcTemplate(dataSource);
	}

	// ==================== 圈选令牌 ====================

	/**
	 * 签发圈选令牌：应用归属校验（本租户应用，0 行 400）→ 48 位随机 hex → Redis HASH {appKey,tenantId,userId}
	 * TTL {@value TrackConstants#VISUAL_TOKEN_TTL_SECONDS}s。返回 {token, url, expireSeconds}——
	 * url = targetUrl 按 ?/& 追加 {@value TrackConstants#VISUAL_INSPECT_PARAM}={token}；targetUrl 空则 url=null（前端自拼 origin）。
	 */
	public Map<String, Object> createToken(String appKey, String targetUrl, long userId) {
		assertAppKey(appKey);
		String appTenant = assertAppVisible(appKey, currentTenant());
		String token = randomHex(TrackConstants.VISUAL_TOKEN_RANDOM_LEN);
		String key = TrackConstants.VISUAL_TOKEN_KEY_PREFIX + token;
		redis.opsForHash().putAll(key, Map.of(
			"appKey", appKey,
			"tenantId", appTenant == null ? "" : appTenant,
			"userId", String.valueOf(userId)));
		redis.expire(key, Duration.ofSeconds(TrackConstants.VISUAL_TOKEN_TTL_SECONDS));
		Map<String, Object> data = new LinkedHashMap<>();
		data.put("token", token);
		data.put("url", inspectUrl(targetUrl, token));
		data.put("expireSeconds", TrackConstants.VISUAL_TOKEN_TTL_SECONDS);
		return data;
	}

	// ==================== 草稿上报（匿名采集链路） ====================

	/**
	 * 草稿上报（/track/visual/draft 匿名端点，SDK inspect 面板提交）：
	 * token 必填 → 令牌 HASH 校验（不存在/过期 401）→ 分钟窗限流（token 前 8 段+IP 双段键，超
	 * {@value TrackConstants#VISUAL_DRAFT_RATE_LIMIT} 429）→ 字段校验（event_name 必过 CUSTOM_EVENT_NAME 正则；
	 * selector 必填 ≤{@value TrackConstants#VISUAL_SELECTOR_MAX_LEN}；match_text/route_path/page_url/element_text
	 * 可空超长截断而非报错）→ 单令牌草稿上限 {@value TrackConstants#VISUAL_DRAFT_MAX_PER_TOKEN}（满 400）→
	 * 右推草稿列表（元素 JSON 含服务端生成的 draftId 与 ts），令牌/草稿两键 EXPIRE 刷新为令牌 TTL。
	 */
	public void ingestDraft(JsonNode root, String ip) {
		String token = text(root, "token");
		if (token == null) {
			throw new TrackCollectException(400, "缺少 token");
		}
		String tokenKey = TrackConstants.VISUAL_TOKEN_KEY_PREFIX + token;
		if (redis.opsForHash().entries(tokenKey).isEmpty()) {
			throw new TrackCollectException(401, "令牌无效或已过期");
		}
		assertDraftRateLimit(token, ip);

		String eventName = text(root, "event_name");
		if (eventName == null || !TrackConstants.CUSTOM_EVENT_NAME.matcher(eventName).matches()) {
			throw new TrackCollectException(400, "事件名不合法（字母开头，仅字母/数字/下划线，≤64 位）");
		}
		String selector = text(root, "selector");
		if (selector == null || selector.length() > TrackConstants.VISUAL_SELECTOR_MAX_LEN) {
			throw new TrackCollectException(400, "selector 必填且 ≤" + TrackConstants.VISUAL_SELECTOR_MAX_LEN + " 字");
		}
		String routePath = truncate(text(root, "route_path"), TrackConstants.DIM_MAX_LEN);
		String matchText = truncate(text(root, "match_text"), TrackConstants.VISUAL_MATCH_TEXT_MAX_LEN);
		String pageUrl = truncate(text(root, "page_url"), TrackConstants.URL_MAX_LEN);
		String elementText = truncate(text(root, "element_text"), TrackConstants.VISUAL_MATCH_TEXT_MAX_LEN);

		String draftKey = TrackConstants.VISUAL_DRAFT_KEY_PREFIX + token;
		Long len = redis.opsForList().size(draftKey);
		if (len != null && len >= TrackConstants.VISUAL_DRAFT_MAX_PER_TOKEN) {
			throw new TrackCollectException(400, "草稿已满请先确认");
		}
		Map<String, Object> draft = new LinkedHashMap<>();
		draft.put("draftId", UUID.randomUUID().toString());
		draft.put("eventName", eventName);
		draft.put("selector", selector);
		draft.put("routePath", routePath);
		draft.put("matchText", matchText);
		draft.put("pageUrl", pageUrl);
		draft.put("elementText", elementText);
		draft.put("ts", System.currentTimeMillis());
		String json;
		try {
			json = PLAIN_MAPPER.writeValueAsString(draft);
		} catch (JsonProcessingException e) {
			throw new IllegalStateException("草稿 JSON 序列化失败", e);
		}
		redis.opsForList().rightPush(draftKey, json);
		// 草稿活跃续期：草稿键与令牌键同 TTL（令牌期内可持续圈选）
		redis.expire(draftKey, Duration.ofSeconds(TrackConstants.VISUAL_TOKEN_TTL_SECONDS));
		redis.expire(tokenKey, Duration.ofSeconds(TrackConstants.VISUAL_TOKEN_TTL_SECONDS));
		log.debug("圈选草稿接收：token={} eventName={}", token.substring(0, 8), eventName);
	}

	// ==================== 管理端草稿 ====================

	/** 草稿列表（令牌 HASH 校验 + 租户归属校验后 LRANGE 全量解析；元素为上报时写入的草稿 JSON） */
	public List<Map<String, Object>> drafts(String token) {
		requireToken(token);
		List<String> items = redis.opsForList().range(TrackConstants.VISUAL_DRAFT_KEY_PREFIX + token, 0, -1);
		List<Map<String, Object>> drafts = new ArrayList<>();
		if (items == null) {
			return drafts;
		}
		for (String raw : items) {
			try {
				drafts.add(PLAIN_MAPPER.readValue(raw, PLAIN_MAPPER.getTypeFactory()
					.constructMapType(LinkedHashMap.class, String.class, Object.class)));
			} catch (JsonProcessingException e) {
				log.warn("圈选草稿元素 JSON 非法，跳过：{}", e.getMessage());
			}
		}
		return drafts;
	}

	/**
	 * 草稿确认成规则：找到草稿（不存在 400）→ 事件名（缺省取草稿值）过正则 → 自然键查 track_visual_rule
	 * （存在 UPDATE status=1，否则 INSERT：tenant_id=令牌归属租户、source=visual、create_by=当前用户）→
	 * 同步 upsert track_event_def（不存在才插，display_name=事件名、description=圈选创建、status=1）→
	 * LREM 草稿 → 失效配置/事件定义缓存 → 返回规则行。
	 */
	public TrackVisualRule confirmDraft(String token, String draftId, String eventName, long userId) {
		Map<Object, Object> hash = requireToken(token);
		String appKey = String.valueOf(hash.get("appKey"));
		String tokenTenant = String.valueOf(hash.get("tenantId"));
		String found = findDraft(token, draftId);
		if (found == null) {
			throw new ServiceException("草稿不存在或已处理");
		}
		Map<String, Object> draft = parseDraft(found);
		String name = eventName == null || eventName.isBlank()
			? String.valueOf(draft.get("eventName")) : eventName.trim();
		if (!TrackConstants.CUSTOM_EVENT_NAME.matcher(name).matches()) {
			throw new ServiceException("事件名不合法（字母开头，仅字母/数字/下划线，≤64 位）");
		}
		String selector = String.valueOf(draft.get("selector"));
		String routePath = draft.get("routePath") == null ? null : String.valueOf(draft.get("routePath"));
		String matchText = draft.get("matchText") == null ? null : String.valueOf(draft.get("matchText"));

		TrackVisualRule rule = findByNaturalKey(appKey, name, selector, routePath, matchText);
		if (rule != null) {
			// 重复圈选 = 更新而非堆行（自然键唯一索引 uk_visual_rule 兜底同口径）：重新启用，归属不改
			rule.setStatus(1);
			rule.setUpdateBy(userId);
			rule.sanitizeForUpdate();
			visualRuleMapper.update(rule, true);
		} else {
			rule = new TrackVisualRule();
			rule.setAppKey(appKey);
			rule.setEventName(name);
			rule.setSelector(selector);
			rule.setRoutePath(routePath);
			rule.setMatchText(matchText);
			rule.setStatus(1);
			rule.setSource(TrackConstants.VISUAL_RULE_SOURCE);
			rule.setTenantId(tokenTenant);
			rule.setCreateBy(userId);
			rule.sanitizeForInsert();
			try {
				visualRuleMapper.insertSelective(rule);
			} catch (DuplicateKeyException e) {
				// 管理端操作竞态可忽略：并发撞自然键唯一索引转友好提示
				throw new ServiceException("规则已存在");
			}
		}
		upsertEventDef(appKey, name, tokenTenant);
		redis.opsForList().remove(TrackConstants.VISUAL_DRAFT_KEY_PREFIX + token, 1, found);
		visualRuleService.evict(appKey);
		eventDefService.evict(appKey, name);
		return rule;
	}

	/** 草稿丢弃：找到草稿 LREM 删除（不存在 400） */
	public void discardDraft(String token, String draftId) {
		requireToken(token);
		String found = findDraft(token, draftId);
		if (found == null) {
			throw new ServiceException("草稿不存在或已处理");
		}
		redis.opsForList().remove(TrackConstants.VISUAL_DRAFT_KEY_PREFIX + token, 1, found);
	}

	// ==================== 规则 CRUD ====================

	/** 规则分页：appKey 必填 + status 可选（本租户行级隔离由 Flex 插件自动拼条件），update_time 倒序 */
	public Page<TrackVisualRule> rulePage(String appKey, Integer status, long pageNum, long pageSize) {
		assertAppKey(appKey);
		QueryWrapper query = QueryWrapper.create().where("app_key = ?", appKey).orderBy("update_time", false);
		if (status != null) {
			query.and("status = ?", status);
		}
		return visualRuleMapper.paginate(pageNum, Math.min(pageSize, TrackConstants.QUERY_PAGE_SIZE_MAX), query);
	}

	/**
	 * 规则编辑：仅 eventName（正则校验 400）/routePath/matchText/status 可改，selector 只读（入参忽略——
	 * 改 selector = 重新圈选）；可编辑字段清空语义（传空串清空为 NULL，缺省 null 保持不变）。
	 * 改事件名同步 upsert track_event_def（同 confirm 逻辑）；改后撞自然键唯一索引转 400「规则已存在」。
	 */
	public TrackVisualRule ruleSubmit(TrackVisualRule body, long userId) {
		if (body.getId() == null) {
			throw new ServiceException("缺少 id");
		}
		TrackVisualRule rule = visualRuleMapper.selectOneByQuery(QueryWrapper.create().where("id = ?", body.getId()));
		if (rule == null) {
			throw new ServiceException("规则不存在或无权限");
		}
		String newName = rule.getEventName();
		if (body.getEventName() != null) {
			newName = body.getEventName().trim();
			if (!TrackConstants.CUSTOM_EVENT_NAME.matcher(newName).matches()) {
				throw new ServiceException("事件名不合法（字母开头，仅字母/数字/下划线，≤64 位）");
			}
		}
		String newRoute = body.getRoutePath() == null ? rule.getRoutePath()
			: normalizeNullable(body.getRoutePath(), TrackConstants.DIM_MAX_LEN, "routePath");
		String newMatch = body.getMatchText() == null ? rule.getMatchText()
			: normalizeNullable(body.getMatchText(), TrackConstants.VISUAL_MATCH_TEXT_MAX_LEN, "matchText");
		Integer newStatus = rule.getStatus();
		if (body.getStatus() != null) {
			if (body.getStatus() != 0 && body.getStatus() != 1) {
				throw new ServiceException("status 仅支持 0/1");
			}
			newStatus = body.getStatus();
		}
		boolean renamed = !Objects.equals(newName, rule.getEventName());
		if (renamed || !Objects.equals(newRoute, rule.getRoutePath()) || !Objects.equals(newMatch, rule.getMatchText())) {
			TrackVisualRule dup = findByNaturalKey(rule.getAppKey(), newName, rule.getSelector(), newRoute, newMatch);
			if (dup != null && !Objects.equals(dup.getId(), rule.getId())) {
				throw new ServiceException("规则已存在");
			}
		}
		// UpdateChain 显式列更新：route_path/match_text 清空语义需写 NULL（实体 update(ignoreNulls) 会跳过 null 列）
		rule.setEventName(newName);
		rule.setRoutePath(newRoute);
		rule.setMatchText(newMatch);
		rule.setStatus(newStatus);
		rule.setUpdateBy(userId);
		try {
			UpdateChain.of(TrackVisualRule.class)
				.set("event_name", newName)
				.set("route_path", newRoute)
				.set("match_text", newMatch)
				.set("status", newStatus)
				.set("update_by", userId)
				.setRaw("update_time", "now()")
				.where("id = ?", rule.getId())
				.update();
		} catch (DuplicateKeyException e) {
			throw new ServiceException("规则已存在");
		}
		if (renamed) {
			upsertEventDef(rule.getAppKey(), newName, rule.getTenantId());
			eventDefService.evict(rule.getAppKey(), newName);
		}
		visualRuleService.evict(rule.getAppKey());
		return rule;
	}

	/** 规则删除（逻辑删除；缓存即时失效后 config 不再下发） */
	public void ruleRemove(long id) {
		TrackVisualRule rule = visualRuleMapper.selectOneByQuery(QueryWrapper.create().where("id = ?", id));
		if (rule == null) {
			throw new ServiceException("规则不存在或无权限");
		}
		visualRuleMapper.deleteByQuery(QueryWrapper.create().where("id = ?", id));
		visualRuleService.evict(rule.getAppKey());
	}

	// ==================== 内部工具 ====================

	/** 管理端令牌前置：HASH 不存在 400「令牌已过期」；HASH.tenantId 与当前租户一致（null=超管放行） */
	private Map<Object, Object> requireToken(String token) {
		if (token == null || token.isBlank()) {
			throw new ServiceException("缺少 token");
		}
		Map<Object, Object> hash = redis.opsForHash().entries(TrackConstants.VISUAL_TOKEN_KEY_PREFIX + token);
		if (hash.isEmpty()) {
			throw new ServiceException("令牌已过期");
		}
		String current = currentTenant();
		if (current != null && !current.equals(String.valueOf(hash.get("tenantId")))) {
			throw new ServiceException("无权操作该令牌");
		}
		return hash;
	}

	/** 按 draftId 找草稿原始 JSON 串（LREM 需原值）；找不到返回 null */
	private String findDraft(String token, String draftId) {
		if (draftId == null || draftId.isBlank()) {
			throw new ServiceException("缺少 draftId");
		}
		List<String> items = redis.opsForList().range(TrackConstants.VISUAL_DRAFT_KEY_PREFIX + token, 0, -1);
		if (items == null) {
			return null;
		}
		for (String raw : items) {
			Map<String, Object> draft = parseDraft(raw);
			if (draftId.equals(draft.get("draftId"))) {
				return raw;
			}
		}
		return null;
	}

	private Map<String, Object> parseDraft(String raw) {
		try {
			return PLAIN_MAPPER.readValue(raw, PLAIN_MAPPER.getTypeFactory()
				.constructMapType(LinkedHashMap.class, String.class, Object.class));
		} catch (JsonProcessingException e) {
			throw new ServiceException("草稿数据损坏");
		}
	}

	/**
	 * 自然键查询（与 uk_visual_rule 同口径）：app_key + event_name + selector +
	 * coalesce(route_path,'') + coalesce(match_text,'')；is_deleted=0 由 Flex 逻辑删除自动拼。
	 */
	private TrackVisualRule findByNaturalKey(String appKey, String eventName, String selector,
											 String routePath, String matchText) {
		return visualRuleMapper.selectOneByQuery(QueryWrapper.create()
			.where("app_key = ?", appKey)
			.and("event_name = ?", eventName)
			.and("selector = ?", selector)
			.and("coalesce(route_path, '') = ?", routePath == null ? "" : routePath)
			.and("coalesce(match_text, '') = ?", matchText == null ? "" : matchText));
	}

	/** 同步 track_event_def：该 (app_key, event_name) 定义不存在才插（圈选创建即管），存在不动 */
	private void upsertEventDef(String appKey, String eventName, String tenantId) {
		TrackEventDef def = eventDefMapper.selectOneByQuery(
			QueryWrapper.create().where("app_key = ?", appKey).and("event_name = ?", eventName));
		if (def != null) {
			return;
		}
		def = new TrackEventDef();
		def.setAppKey(appKey);
		def.setEventName(eventName);
		def.setDisplayName(eventName);
		def.setDescription("圈选创建");
		def.setStatus(1);
		def.setTenantId(tenantId);
		def.sanitizeForInsert();
		eventDefMapper.insertSelective(def);
	}

	/** token+IP 分钟窗限流：INCR 首置 EXPIRE 70s；超限 429；Redis 故障 fail-open（同事件摄入口径） */
	private void assertDraftRateLimit(String token, String ip) {
		String key = TrackConstants.VISUAL_RATE_LIMIT_KEY_PREFIX + token.substring(0, 8) + ":" + ip + ":"
			+ MINUTE_FORMAT.format(LocalDateTime.now());
		try {
			Long count = redis.opsForValue().increment(key);
			if (count != null && count == 1L) {
				redis.expire(key, Duration.ofSeconds(TrackConstants.RATE_LIMIT_EXPIRE_SECONDS));
			}
			if (count != null && count > TrackConstants.VISUAL_DRAFT_RATE_LIMIT) {
				throw new TrackCollectException(429, "请求过于频繁");
			}
		} catch (TrackCollectException e) {
			throw e;
		} catch (RuntimeException e) {
			// Redis 不可用降级：限流失效但草稿链路不停（单令牌草稿上限仍在兜底）
			log.warn("圈选草稿限流计数 Redis 不可用，本次放行：{}", e.getMessage());
		}
	}

	/** 应用可见性（Redis 通道无 SQL 租户条件，先显式校验本租户归属；0 行 = 不存在或他租户），返回应用归属租户 */
	private String assertAppVisible(String appKey, String tenant) {
		List<Object> args = new ArrayList<>(List.of(appKey));
		String sql = "SELECT tenant_id FROM track_app WHERE app_key = ? AND is_deleted = 0" + tenantFrag(tenant, args);
		List<String> rows = jdbc.queryForList(sql, String.class, args.toArray());
		if (rows.isEmpty()) {
			throw new ServiceException("应用不存在或无权访问");
		}
		return rows.get(0);
	}

	/** 当前请求租户（null=超管查看全部；无上下文 fail-closed 抛异常，与 Flex 租户插件同语义） */
	private String currentTenant() {
		Object[] ids = TenantContext.resolveTenantIds();
		return ids == null ? null : String.valueOf(ids[0]);
	}

	/** 租户条件片段：有租户 → 拼 {@code AND tenant_id = ?} 并把值入参；null（查看全部）→ 空串 */
	private String tenantFrag(String tenant, List<Object> args) {
		if (tenant == null) {
			return "";
		}
		args.add(tenant);
		return " AND tenant_id = ?";
	}

	private void assertAppKey(String appKey) {
		if (appKey == null || appKey.isBlank() || appKey.length() > TrackConstants.APP_KEY_MAX_LEN) {
			throw new ServiceException("缺少或非法 appKey");
		}
	}

	/** inspect URL 拼接：targetUrl 非空按 ?/& 追加圈选激活参数；空返回 null（前端自拼 origin） */
	private String inspectUrl(String targetUrl, String token) {
		if (targetUrl == null || targetUrl.isBlank()) {
			return null;
		}
		String url = targetUrl.trim();
		return url + (url.contains("?") ? "&" : "?") + TrackConstants.VISUAL_INSPECT_PARAM + "=" + token;
	}

	private String randomHex(int len) {
		StringBuilder sb = new StringBuilder(len);
		for (int i = 0; i < len; i++) {
			sb.append(Integer.toHexString(RANDOM.nextInt(16)));
		}
		return sb.toString();
	}

	/** 可空文本字段归一：trim 后空串归 NULL（参与 coalesce 唯一键归一），超长 400 */
	private String normalizeNullable(String value, int maxLen, String field) {
		String v = value.trim();
		if (v.isEmpty()) {
			return null;
		}
		if (v.length() > maxLen) {
			throw new ServiceException(field + " ≤" + maxLen + " 字");
		}
		return v;
	}

	/** 采集侧可空字段截断（而非报错）：超长截到上限 */
	private String truncate(String value, int maxLen) {
		if (value == null || value.length() <= maxLen) {
			return value;
		}
		return value.substring(0, maxLen);
	}

	/** 文本字段（缺失/NULL/容器/空白一律归 null，同 TrackIngestService 口径） */
	private String text(JsonNode node, String field) {
		if (node == null) {
			return null;
		}
		JsonNode value = node.get(field);
		if (value == null || value.isNull() || value.isContainerNode()) {
			return null;
		}
		String text = value.asText();
		return text == null || text.isBlank() ? null : text;
	}
}
