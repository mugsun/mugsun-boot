package com.mugsun.boot.track;

import com.mugsun.boot.common.constant.TrackConstants;
import com.mugsun.boot.tenant.TenantContext;
import com.mugsun.boot.track.entity.TrackEventDef;
import com.mugsun.boot.track.mapper.TrackEventDefMapper;
import com.mybatisflex.core.query.QueryWrapper;
import org.springframework.stereotype.Service;

import java.util.concurrent.ConcurrentHashMap;

/**
 * 事件定义停用判定（G105，§4.5 语义兑现）：collect 摄入链路的事件级拒收数据源。
 * <p>语义：track_event_def 无定义/已删 = 未停用（自动注册语义默认启用）；status=0 = 停用拒收。
 * <p>本地 {@link ConcurrentHashMap} 缓存 {@value TrackConstants#EVENT_DEF_CACHE_TTL_MS}ms（含「未停用」负缓存，
 * 防高热事件名高频打库），键 {@code appKey + '\0' + eventName}。<b>多副本生效延迟说明</b>：各副本独立缓存、
 * 无失效广播，管理端变更经 {@link #evict} 即时生效本副本，其余副本最坏
 * {@value TrackConstants#EVENT_DEF_CACHE_TTL_MS}ms 后生效（同 {@link TrackAppService} 口径，可接受）。
 * <p>track_event_def 带 tenant_id 列且 collect 无会话上下文，查询经 {@link TenantContext#ignore} 显式放行
 * （appKey+eventName 本身即检索键，租户隔离由写入端裁定 tenant_id 保证）；{@link TrackDS} 注解路由埋点库。
 */
@Service
@TrackDS
public class TrackEventDefService {

	/** 缓存条目（disabled 即判定结论，无定义/已删 = false 负缓存；expireAt 到期即失效查库） */
	private record CacheEntry(boolean disabled, long expireAt) {
	}

	private final TrackEventDefMapper trackEventDefMapper;
	private final ConcurrentHashMap<String, CacheEntry> cache = new ConcurrentHashMap<>();

	public TrackEventDefService(TrackEventDefMapper trackEventDefMapper) {
		this.trackEventDefMapper = trackEventDefMapper;
	}

	/** 事件是否已停用（缓存 30s；无定义/已删/状态启用 = false，status=0 = true） */
	public boolean isDisabled(String appKey, String eventName) {
		long now = System.currentTimeMillis();
		String key = appKey + '\0' + eventName;
		CacheEntry hit = cache.get(key);
		if (hit != null && hit.expireAt() > now) {
			return hit.disabled();
		}
		TrackEventDef def = TenantContext.ignore(() -> trackEventDefMapper.selectOneByQuery(
			QueryWrapper.create().eq("app_key", appKey).eq("event_name", eventName)));
		boolean disabled = def != null && def.getStatus() != null && def.getStatus() == 0;
		cache.put(key, new CacheEntry(disabled, now + TrackConstants.EVENT_DEF_CACHE_TTL_MS));
		return disabled;
	}

	/** 失效指定事件定义的本地缓存（管理端变更后调用，采集端即时感知，不等 TTL） */
	public void evict(String appKey, String eventName) {
		cache.remove(appKey + '\0' + eventName);
	}
}
