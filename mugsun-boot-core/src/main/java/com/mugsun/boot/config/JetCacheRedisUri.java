package com.mugsun.boot.config;

import io.lettuce.core.RedisURI;

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Objects;

/**
 * 把 {@code spring.data.redis} 的 host/port/password/database 合成 JetCache lettuce 所需的
 * {@code uri}。JetCache 2.7 的 redis.lettuce 只认 uri、没有分字段口令；口令若裸拼进
 * {@code redis://:口令@host}，含 {@code @ : / ? # %} 等时 Lettuce 会解析错 host/db。
 * <p>口令按 userinfo 做百分号编码（比 Lettuce 自带 {@code urlEncode} 更严，会编码 {@code /}），
 * 再经 {@link RedisURI#create(URI)} 回解析核对；不一致则抛错，避免静默连错。
 */
public final class JetCacheRedisUri {

	private JetCacheRedisUri() {
	}

	/**
	 * 合成 {@code redis://[ :encodedPassword@]host:port/db}。
	 * @param host 非空
	 * @param port 1–65535
	 * @param password 可空；空或空白视为无口令
	 * @param database ≥0
	 */
	public static String build(String host, int port, String password, int database) {
		if (host == null || host.isBlank()) {
			throw new IllegalArgumentException("Redis host 不能为空");
		}
		if (port < 1 || port > 65535) {
			throw new IllegalArgumentException("Redis port 非法: " + port);
		}
		if (database < 0) {
			throw new IllegalArgumentException("Redis database 非法: " + database);
		}
		String h = host.trim();
		String uri;
		if (password == null || password.isBlank()) {
			uri = "redis://" + h + ":" + port + "/" + database;
		} else {
			uri = "redis://:" + encodeUserInfo(password) + "@" + h + ":" + port + "/" + database;
		}
		assertRoundTrip(uri, h, port, password == null ? "" : password, database);
		return uri;
	}

	/** userinfo 百分号编码：UTF-8，空格→%20（不用 +），并编码 *。 */
	public static String encodeUserInfo(String raw) {
		Objects.requireNonNull(raw, "raw");
		return URLEncoder.encode(raw, StandardCharsets.UTF_8)
			.replace("+", "%20")
			.replace("*", "%2A")
			.replace("%7E", "~");
	}

	/**
	 * 与 JetCache 相同路径回解析：{@code URI.create} → {@code RedisURI.create(URI)}，
	 * 核对 host/port/database/password；失败则 fail fast（异常信息不含口令明文）。
	 */
	public static void assertRoundTrip(String uri, String host, int port, String password, int database) {
		RedisURI parsed;
		try {
			parsed = RedisURI.create(URI.create(uri));
		} catch (RuntimeException e) {
			throw new IllegalStateException(
				"jetcache Redis URI 无法解析（口令可能含未编码特殊字符），已拒绝启动", e);
		}
		String gotPw = parsed.getPassword() == null ? "" : new String(parsed.getPassword());
		String expectPw = password == null ? "" : password;
		if (!host.equals(parsed.getHost())
			|| port != parsed.getPort()
			|| database != parsed.getDatabase()
			|| !expectPw.equals(gotPw)) {
			throw new IllegalStateException(
				"jetcache Redis URI 回解析与 spring.data.redis 不一致（主机/端口/库/口令），已拒绝启动");
		}
	}
}
