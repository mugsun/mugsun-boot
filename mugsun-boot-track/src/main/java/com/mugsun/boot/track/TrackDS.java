package com.mugsun.boot.track;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 标注「全部 DB 访问都走路由到埋点独立库（track 数据源）」的业务类/方法。
 * <p>由 {@link TrackDsAspect} 环绕压栈 {@code DataSourceKey.use(TrackConstants.DS_KEY)}，方法结束弹栈，
 * 与租户动态数据源体系（@TenantRouted/TenantDataSourceRegistry）互不干扰。
 * <p><b>使用纪律</b>：
 * <br>① 标注范围内的<em>全部</em> DB 访问都会走埋点库——范围内严禁访问 sys_* 等业务表（埋点库无此表）；
 * <br>② 摄入消费线程（跨租户混合批写）另需 {@code TenantContext.ignore} 包裹——严禁继承发起请求的租户上下文，
 * 按每行自带 tenant_id 显式写，避免 MyBatis-Flex 租户行级插件对跨租户混合批写拼错租户条件。
 */
@Target({ElementType.TYPE, ElementType.METHOD})
@Retention(RetentionPolicy.RUNTIME)
public @interface TrackDS {
}
