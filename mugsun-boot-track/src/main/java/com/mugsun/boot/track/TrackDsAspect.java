package com.mugsun.boot.track;

import com.mugsun.boot.common.constant.TrackConstants;
import com.mybatisflex.core.datasource.DataSourceKey;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * 埋点数据源路由切面：{@link TrackDS} 标注的业务类/方法执行前压栈 {@code DataSourceKey.use("track")}，
 * 方法结束弹栈（{@link DataSourceKey} 为 ThreadLocal 栈，{@code use}/{@code clear} 精确配对、可嵌套重入）。
 * <p>参照 {@code TenantRoutedAspect} 的声明式路由，替代散落的手工 {@code DataSourceKey.use} 包裹。
 * <p><b>注意</b>：标注范围内全部 DB 访问走埋点库；摄入消费线程（跨租户混合批写）另需
 * {@code TenantContext.ignore} 包裹，避免租户行级插件对跨租户批写拼错条件（见 {@link TrackDS} 使用纪律）。
 */
@Aspect
@Component
@Order(0)
public class TrackDsAspect {

	@Around("@within(com.mugsun.boot.track.TrackDS) || @annotation(com.mugsun.boot.track.TrackDS)")
	public Object route(ProceedingJoinPoint pjp) throws Throwable {
		DataSourceKey.use(TrackConstants.DS_KEY);
		try {
			return pjp.proceed();
		} finally {
			DataSourceKey.clear();
		}
	}
}
