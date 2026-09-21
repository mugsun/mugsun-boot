package com.mugsun.boot.datasource;

import com.mugsun.boot.tenant.TenantContext;
import com.mybatisflex.core.datasource.DataSourceKey;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * 租户数据源自动路由切面：{@link TenantRouted} 标注的业务类/方法执行前，按当前租户隔离策略解析路由键并压栈，
 * 方法结束弹栈（{@link DataSourceKey} 为 ThreadLocal 栈，{@code use}/{@code clear} 精确配对、可嵌套重入）。
 * <p>取代 BizCustomerController 手工 {@code DataSourceKey.use} 包裹，实现声明式路由。
 */
@Aspect
@Component
@Order(0)
public class TenantRoutedAspect {

	private final TenantDataSourceRegistry registry;

	public TenantRoutedAspect(TenantDataSourceRegistry registry) {
		this.registry = registry;
	}

	@Around("@within(com.mugsun.boot.datasource.TenantRouted) || @annotation(com.mugsun.boot.datasource.TenantRouted)")
	public Object route(ProceedingJoinPoint pjp) throws Throwable {
		DataSourceKey.use(registry.resolveDsKey(TenantContext.current()));
		try {
			return pjp.proceed();
		} finally {
			DataSourceKey.clear();
		}
	}
}
