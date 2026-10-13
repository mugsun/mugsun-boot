package com.mugsun.boot.track.config;

import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.env.EnvironmentPostProcessor;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;
import org.springframework.util.StringUtils;

/**
 * 埋点库地址回落：TRACK_JDBC_URL → MUGSUN_DB_URL → MUGSUN_DB_HOST/PORT。
 * <p>
 * 只设了 MUGSUN_DB_URL 时，沿用其主机、端口与参数，仅把库名换成 {@code mugsun_track}（保持与业务库同实例独立 database 的隔离），
 * 结果写入 {@code mugsun.track.derived-jdbc-url}，由 mugsun-module-track.yml 引用。无法识别的 URL 不派生，回落到 HOST/PORT。
 */
public class TrackDbUrlEnvironmentPostProcessor implements EnvironmentPostProcessor {

	public static final String DERIVED_KEY = "mugsun.track.derived-jdbc-url";

	static final String TRACK_DB = "mugsun_track";

	/** jdbc:xxx://authority/dbname[?params]；authority 可含多主机。 */
	private static final Pattern URL = Pattern.compile("^(jdbc:[a-z0-9]+://[^/?]+)/([^/?;]+)(.*)$");

	@Override
	public void postProcessEnvironment(ConfigurableEnvironment env, SpringApplication app) {
		if (StringUtils.hasText(env.getProperty("TRACK_JDBC_URL"))) {
			return;
		}
		String derived = derive(env.getProperty("MUGSUN_DB_URL"));
		if (derived != null) {
			env.getPropertySources().addLast(new MapPropertySource("mugsunTrackDerivedUrl", Map.of(DERIVED_KEY, derived)));
		}
	}

	static String derive(String mainUrl) {
		if (!StringUtils.hasText(mainUrl)) {
			return null;
		}
		Matcher m = URL.matcher(mainUrl.trim());
		return m.matches() ? m.group(1) + "/" + TRACK_DB + m.group(3) : null;
	}
}
