package com.mugsun.boot;

import com.alicp.jetcache.Cache;
import com.alicp.jetcache.CacheManager;
import com.alicp.jetcache.MultiLevelCache;
import com.alicp.jetcache.ProxyCache;
import com.mugsun.boot.system.service.ParamService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * #90：系统参数 BOTH+syncLocal——绕过服务改库后本地仍命中旧值，evict 后各层失效并读到新值。
 * <p>单 JVM 用「直写 DB + 本地仍脏」模拟多机本地未失效；evict（与写/删同路径）应清掉本地与远程。
 * <p>另断言 {@code broadcastChannel} 已装上 BroadcastManager，避免 channel 为空时静默假绿。
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ParamCacheSyncApiTest extends AbstractIntegrationTest {

	private static final String KEY = "it.param.sync." + System.currentTimeMillis();

	@Autowired
	private ParamService paramService;

	@Autowired
	private CacheManager cacheManager;

	@Test
	@DisplayName("直写 DB 后仍命中旧缓存；evict 后读到新值；缓存为两级")
	void evictClearsLocalAfterDirectDbUpdate() {
		paramService.setValue(KEY, "v1");
		assertThat(paramService.getValue(KEY)).isEqualTo("v1");

		Cache<?, ?> cache = cacheManager.getCache("param:");
		assertThat(cache).as("首次取值后 param: 缓存应已创建").isNotNull();
		Cache<?, ?> target = cache;
		while (target instanceof ProxyCache<?, ?> proxy) {
			target = proxy.getTargetCache();
		}
		assertThat(target).as("param: 应为两级缓存（LOCAL+REMOTE）").isInstanceOf(MultiLevelCache.class);
		assertThat(((MultiLevelCache<?, ?>) target).caches()).hasSize(2);
		assertThat(cacheManager.getBroadcastManager("default"))
			.as("remote.default.broadcastChannel 非空时才有 BroadcastManager；空则 syncLocal 静默无效")
			.isNotNull();

		int updated = jdbc.update(
			"UPDATE sys_param SET param_value = ? WHERE param_key = ? AND is_deleted = 0",
			"v2", KEY);
		assertThat(updated).isEqualTo(1);
		assertThat(paramService.getValue(KEY)).as("未 evict 前应仍命中缓存旧值").isEqualTo("v1");

		paramService.evict(KEY);
		assertThat(paramService.getValue(KEY)).as("evict 后应读库新值").isEqualTo("v2");

		jdbc.update("DELETE FROM sys_param WHERE param_key = ?", KEY);
		paramService.evict(KEY);
	}
}
