package com.mugsun.boot.demo;

import com.mybatisflex.core.datasource.DataSourceKey;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import java.util.function.Supplier;

/**
 * 日志写入的数据源路由：只读演示模式下，登录/访问/错误/操作/通用日志改走独立的 {@code log} 数据源
 * （账号只授日志表 INSERT），业务数据源保持只读。非 demo、或 demo 未配置 log 数据源时原样走业务数据源。
 * <p>demo 下日志写入失败只告警、绝不抛出，保证漏配权限也不会打断登录等主流程。
 */
@Component
public class LogDataSourceRouter {

	/** mybatis-flex 数据源键 */
	public static final String LOG_DS_KEY = "log";

	private static final Logger log = LoggerFactory.getLogger(LogDataSourceRouter.class);

	private final boolean demo;
	private final boolean route;

	public LogDataSourceRouter(@Value("${" + DemoConstants.ENABLED_PROPERTY + ":false}") boolean demo, Environment env) {
		this.demo = demo;
		this.route = demo && env.containsProperty("mybatis-flex.datasource." + LOG_DS_KEY + ".url");
		if (demo) {
			if (route) {
				log.info("[demo] 日志写入走独立数据源 {}", LOG_DS_KEY);
			} else {
				log.warn("[demo] 未配置 mybatis-flex.datasource.log，日志写入将落到业务（只读）账号并失败");
			}
		}
	}

	public boolean routed() {
		return route;
	}

	/** 执行一次日志写入 */
	public void write(Runnable action) {
		if (!demo) {
			action.run();
			return;
		}
		try {
			if (route) {
				DataSourceKey.use(LOG_DS_KEY, action);
			} else {
				action.run();
			}
		} catch (RuntimeException e) {
			log.warn("[demo] 日志写入失败（不影响主流程）：{}", e.getMessage());
		}
	}

	/** 执行一次带返回值的日志写入；demo 下失败返回 null */
	public <T> T write(Supplier<T> action) {
		if (!demo) {
			return action.get();
		}
		try {
			return route ? DataSourceKey.use(LOG_DS_KEY, action) : action.get();
		} catch (RuntimeException e) {
			log.warn("[demo] 日志写入失败（不影响主流程）：{}", e.getMessage());
			return null;
		}
	}
}
