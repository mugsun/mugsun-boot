package com.mugsun.boot.monitor;

import org.apache.commons.logging.Log;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.env.EnvironmentPostProcessor;
import org.springframework.boot.logging.DeferredLogFactory;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;

import java.util.Map;

/**
 * 访问日志留存 ≥180 天是硬要求（《网络安全法》第二十一条）。{@code mugsun.demo.access-log.max-history}
 * 低于 180（含 0、负数）或不是整数时，改用 180 并打 WARN。在日志系统初始化之前执行，logback 读到的就是修正后的值。
 */
public class AccessLogRetentionEnvironmentPostProcessor implements EnvironmentPostProcessor {

	static final String PROPERTY = "mugsun.demo.access-log.max-history";
	static final int MIN_DAYS = 180;
	static final String SOURCE = "mugsunAccessLogRetention";

	private final Log log;

	public AccessLogRetentionEnvironmentPostProcessor(DeferredLogFactory logFactory) {
		this.log = logFactory.getLog(AccessLogRetentionEnvironmentPostProcessor.class);
	}

	@Override
	public void postProcessEnvironment(ConfigurableEnvironment env, SpringApplication app) {
		String raw = env.getProperty(PROPERTY);
		if (raw == null) {
			return;
		}
		Integer fixed = clamp(raw);
		if (fixed != null) {
			log.warn("[demo] " + PROPERTY + "=" + raw + " 低于访问日志最低留存 " + MIN_DAYS + " 天或无法解析，已改用 " + fixed);
			env.getPropertySources().addFirst(new MapPropertySource(SOURCE, Map.of(PROPERTY, fixed)));
		}
	}

	/** 需要修正时返回修正值，否则返回 null。 */
	static Integer clamp(String raw) {
		try {
			return Integer.parseInt(raw.trim()) < MIN_DAYS ? MIN_DAYS : null;
		} catch (NumberFormatException e) {
			return MIN_DAYS;
		}
	}
}
