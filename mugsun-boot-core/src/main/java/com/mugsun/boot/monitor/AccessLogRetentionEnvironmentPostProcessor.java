package com.mugsun.boot.monitor;

import org.apache.commons.logging.Log;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.env.EnvironmentPostProcessor;
import org.springframework.boot.logging.DeferredLogFactory;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Map;

/**
 * 访问日志留存 ≥180 天是硬要求（《网络安全法》第二十一条）。{@code mugsun.demo.access-log.max-history}
 * 低于 180（含 0、负数）或无法解析时改用 180 并打 WARN；≥180 的小数向下取整（不低于 180）。在日志系统初始化之前执行，logback 读到的就是修正后的值。
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
		Fix fix = evaluate(raw);
		if (fix == null) {
			return;
		}
		if (fix.warn()) {
			log.warn("[demo] " + PROPERTY + "=" + raw + " 低于访问日志最低留存 " + MIN_DAYS + " 天或无法解析，已改用 " + fix.days());
		}
		env.getPropertySources().addFirst(new MapPropertySource(SOURCE, Map.of(PROPERTY, fix.days())));
	}

	/** 修正后的天数；warn 表示原值低于 180 或无法解析。 */
	record Fix(int days, boolean warn) {
	}

	/**
	 * 整数且 ≥180：原样使用，返回 null。小数或超过 int 范围且 ≥180：向下取整（超大值取 Integer.MAX_VALUE），不告警。
	 * 低于 180（含 0、负数）或无法解析：改用 180 并告警。
	 */
	static Fix evaluate(String raw) {
		BigDecimal v;
		try {
			v = new BigDecimal(raw.trim());
		} catch (NumberFormatException e) {
			return new Fix(MIN_DAYS, true);
		}
		if (v.compareTo(BigDecimal.valueOf(MIN_DAYS)) < 0) {
			return new Fix(MIN_DAYS, true);
		}
		BigDecimal floor = v.setScale(0, RoundingMode.FLOOR);
		int days = floor.compareTo(BigDecimal.valueOf(Integer.MAX_VALUE)) > 0 ? Integer.MAX_VALUE : floor.intValueExact();
		boolean plainInt = raw.trim().matches("\\d+") && days == floor.longValue();
		return plainInt ? null : new Fix(days, false);
	}

}
