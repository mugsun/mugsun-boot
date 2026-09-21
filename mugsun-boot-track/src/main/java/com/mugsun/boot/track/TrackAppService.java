package com.mugsun.boot.track;

import com.mugsun.boot.common.constant.TrackConstants;
import com.mugsun.boot.tenant.TenantContext;
import com.mugsun.boot.track.entity.TrackApp;
import com.mugsun.boot.track.mapper.TrackAppMapper;
import com.mybatisflex.core.query.QueryWrapper;
import org.springframework.stereotype.Service;

import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 埋点接入应用查询：collect/config 公开端点的 appKey 校验与配置下发数据源。
 * <p>本地 {@link ConcurrentHashMap} 缓存 {@value TrackConstants#APP_CACHE_TTL_MS}ms（含不存在的负缓存，
 * 防伪 app_key 高频打库）。<b>多副本生效延迟说明</b>：各副本独立缓存、无失效广播，应用关停/采样调整
 * 最坏 {@value TrackConstants#APP_CACHE_TTL_MS}ms 后才在全部副本生效——可接受（运维操作非高频）。
 * <p>track_app 带 tenant_id 列且 collect 无会话上下文，查询经 {@link TenantContext#ignore} 显式放行
 * （appKey 本身即检索键，租户隔离由服务端映射 tenant_id 保证）；{@link TrackDS} 注解路由埋点库。
 */
@Service
@TrackDS
public class TrackAppService {

	/** 缓存条目（app 可空=负缓存；expireAt 到期即失效查库） */
	private record CacheEntry(TrackApp app, long expireAt) {
	}

	private final TrackAppMapper trackAppMapper;
	private final ConcurrentHashMap<String, CacheEntry> cache = new ConcurrentHashMap<>();

	public TrackAppService(TrackAppMapper trackAppMapper) {
		this.trackAppMapper = trackAppMapper;
	}

	/** 按 appKey 查应用（缓存 30s；不存在返回 empty） */
	public Optional<TrackApp> findByAppKey(String appKey) {
		long now = System.currentTimeMillis();
		CacheEntry hit = cache.get(appKey);
		if (hit != null && hit.expireAt() > now) {
			return Optional.ofNullable(hit.app());
		}
		TrackApp app = TenantContext.ignore(() -> trackAppMapper.selectOneByQuery(
			QueryWrapper.create().eq("app_key", appKey)));
		cache.put(appKey, new CacheEntry(app, now + TrackConstants.APP_CACHE_TTL_MS));
		return Optional.ofNullable(app);
	}

	/** 按 appKey 查可采集应用（存在且 enabled=1；否则 empty=拒收） */
	public Optional<TrackApp> findCollectable(String appKey) {
		return findByAppKey(appKey).filter(app -> app.getEnabled() != null && app.getEnabled() == 1);
	}

	/** 失效指定 appKey 的本地缓存（管理端变更/删除后调用，采集端即时感知，不等 TTL） */
	public void evict(String appKey) {
		cache.remove(appKey);
	}
}
