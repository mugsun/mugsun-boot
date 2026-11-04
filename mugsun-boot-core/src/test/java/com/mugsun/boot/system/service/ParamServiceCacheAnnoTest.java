package com.mugsun.boot.system.service;

import com.alicp.jetcache.anno.CacheInvalidate;
import com.alicp.jetcache.anno.CacheType;
import com.alicp.jetcache.anno.Cached;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * #90：ParamService 注解口径——BOTH + syncLocal，写删有 CacheInvalidate。
 */
class ParamServiceCacheAnnoTest {

	@Test
	@DisplayName("cachedGetValue：CacheType.BOTH 且 syncLocal=true")
	void cachedUsesBothWithSyncLocal() throws Exception {
		Cached cached = ParamService.class.getMethod("cachedGetValue", String.class)
			.getAnnotation(Cached.class);
		assertThat(cached).isNotNull();
		assertThat(cached.name()).isEqualTo("param:");
		assertThat(cached.cacheType()).isEqualTo(CacheType.BOTH);
		assertThat(cached.syncLocal()).isTrue();
	}

	@Test
	@DisplayName("evictInternal：按键 CacheInvalidate")
	void evictInvalidatesParamCache() throws Exception {
		CacheInvalidate inv = ParamService.class.getMethod("evictInternal", String.class)
			.getAnnotation(CacheInvalidate.class);
		assertThat(inv).isNotNull();
		assertThat(inv.name()).isEqualTo("param:");
		assertThat(inv.key()).isEqualTo("#paramKey");
	}
}
