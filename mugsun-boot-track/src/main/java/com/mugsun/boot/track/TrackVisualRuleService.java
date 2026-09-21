package com.mugsun.boot.track;

import com.mugsun.boot.common.constant.TrackConstants;
import com.mugsun.boot.tenant.TenantContext;
import com.mugsun.boot.track.entity.TrackVisualRule;
import com.mugsun.boot.track.mapper.TrackVisualRuleMapper;
import com.mybatisflex.core.query.QueryWrapper;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 圈选规则配置下发（G104）：/track/config 的 visualRules 数据源。
 * <p>本地 {@link ConcurrentHashMap} 缓存 {@value TrackConstants#APP_CACHE_TTL_MS}ms（含空规则负缓存，
 * 防无规则应用高频打库），键 appKey。<b>多副本生效延迟说明</b>：各副本独立缓存、无失效广播，管理端
 * 确认/编辑/删除经 {@link #evict} 即时生效本副本，其余副本最坏 {@value TrackConstants#APP_CACHE_TTL_MS}ms
 * 后生效（同 {@link TrackAppService} 口径，可接受，前端文案注明生效延迟）。
 * <p>track_visual_rule 带 tenant_id 列且 config 无会话上下文，查询经 {@link TenantContext#ignore} 显式放行
 * （appKey 本身即检索键即边界——app_key 全局唯一，规则归属由写入端裁定 tenant_id 保证，同
 * {@link TrackAppService} 口径）；{@link TrackDS} 注解路由埋点库。
 */
@Service
@TrackDS
public class TrackVisualRuleService {

	/** 缓存条目（rules 不可变投影列表，可为空列表=负缓存；expireAt 到期即失效查库） */
	private record CacheEntry(List<Map<String, Object>> rules, long expireAt) {
	}

	private final TrackVisualRuleMapper visualRuleMapper;
	private final ConcurrentHashMap<String, CacheEntry> cache = new ConcurrentHashMap<>();

	public TrackVisualRuleService(TrackVisualRuleMapper visualRuleMapper) {
		this.visualRuleMapper = visualRuleMapper;
	}

	/**
	 * 应用启用圈选规则投影（缓存 30s）：status=1 按 update_time 倒序，限
	 * {@value TrackConstants#VISUAL_RULES_MAX} 条；投影 [{event, selector, routePath, matchText}]
	 * （null 直出，SDK 端归一）。
	 */
	public List<Map<String, Object>> enabledRules(String appKey) {
		long now = System.currentTimeMillis();
		CacheEntry hit = cache.get(appKey);
		if (hit != null && hit.expireAt() > now) {
			return hit.rules();
		}
		List<TrackVisualRule> rows = TenantContext.ignore(() -> visualRuleMapper.selectListByQuery(
			QueryWrapper.create()
				.where("app_key = ?", appKey)
				.and("status = 1")
				.orderBy("update_time", false)
				.limit(TrackConstants.VISUAL_RULES_MAX)));
		List<Map<String, Object>> rules = new ArrayList<>(rows.size());
		for (TrackVisualRule row : rows) {
			Map<String, Object> item = new LinkedHashMap<>();
			item.put("event", row.getEventName());
			item.put("selector", row.getSelector());
			item.put("routePath", row.getRoutePath());
			item.put("matchText", row.getMatchText());
			rules.add(item);
		}
		cache.put(appKey, new CacheEntry(rules, now + TrackConstants.APP_CACHE_TTL_MS));
		return rules;
	}

	/** 失效指定 appKey 的规则缓存（管理端确认/编辑/删除后调用，config 端即时感知，不等 TTL） */
	public void evict(String appKey) {
		cache.remove(appKey);
	}
}
