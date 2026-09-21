package com.mugsun.boot.tenant;

import com.alicp.jetcache.support.JacksonKeyConvertor;
import org.springframework.stereotype.Component;

import java.util.function.Function;

/**
 * 租户感知缓存键转换器：在标准键前缀当前租户，使 JetCache（本地/Redis）各缓存按租户隔离，杜绝跨租户命中串号。
 * <p>无租户上下文（平台级/启动）时前缀 {@code -}，与租户缓存自然分隔。经 {@code keyConvertor: bean:tenantKeyConvertor} 全局挂载，各 {@code @Cached} 无需散落改写。
 */
@Component("tenantKeyConvertor")
public class TenantKeyConvertor implements Function<Object, Object> {

	@Override
	public Object apply(Object key) {
		String tenant = TenantContext.current();
		return (tenant == null ? "-" : tenant) + ':' + JacksonKeyConvertor.INSTANCE.apply(key);
	}
}
