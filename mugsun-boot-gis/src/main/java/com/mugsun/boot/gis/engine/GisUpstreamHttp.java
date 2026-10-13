package com.mugsun.boot.gis.engine;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mugsun.boot.gis.GisConstants;
import com.mugsun.core.tool.exception.ServiceException;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Locale;
import java.util.function.Predicate;

/**
 * 上游 HTTP：8s 超时、浏览器 UA、不跟随重定向；JSON / 字节 / POST JSON。
 */
@Component
public class GisUpstreamHttp {

	private static final Duration TIMEOUT = Duration.ofSeconds(8);
	/** 厂商接口（检索 / 逆地理 / 瓦片）也不跟随重定向：3xx 视为失败，避免被引到白名单外 */
	private static final HttpClient HTTP = HttpClient.newBuilder()
		.connectTimeout(TIMEOUT)
		.followRedirects(HttpClient.Redirect.NEVER)
		.build();

	public static final String BROWSER_UA = "Mozilla/5.0";

	/** 当前客户端是否跟随重定向（供单测确认） */
	public static boolean followsRedirects() {
		return HTTP.followRedirects() != HttpClient.Redirect.NEVER || STRICT.followRedirects() != HttpClient.Redirect.NEVER;
	}

	private final ObjectMapper objectMapper;

	public GisUpstreamHttp(ObjectMapper objectMapper) {
		this.objectMapper = objectMapper;
	}

	public String writeJson(Object value) throws Exception {
		return objectMapper.writeValueAsString(value);
	}

	public JsonNode getJson(URI uri, Predicate<String> hostOk) throws Exception {
		assertHost(uri, hostOk);
		HttpRequest req = HttpRequest.newBuilder(uri)
			.timeout(TIMEOUT)
			.header("User-Agent", "Mozilla/5.0")
			.GET()
			.build();
		HttpResponse<String> resp = HTTP.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
		return parseJson(resp);
	}

	public JsonNode postJson(URI uri, String body, Predicate<String> hostOk) throws Exception {
		assertHost(uri, hostOk);
		HttpRequest req = HttpRequest.newBuilder(uri)
			.timeout(TIMEOUT)
			.header("User-Agent", "Mozilla/5.0")
			.header("Content-Type", "application/json")
			.POST(HttpRequest.BodyPublishers.ofString(body == null ? "" : body))
			.build();
		HttpResponse<String> resp = HTTP.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
		return parseJson(resp);
	}

	public HttpResponse<byte[]> getBytes(URI uri, Predicate<String> hostOk) throws Exception {
		assertHost(uri, hostOk);
		HttpRequest req = HttpRequest.newBuilder(uri)
			.timeout(TIMEOUT)
			.header("User-Agent", "Mozilla/5.0")
			.GET()
			.build();
		return HTTP.send(req, HttpResponse.BodyHandlers.ofByteArray());
	}

	/** 不跟随重定向的客户端：自定义瓦片源用，3xx 直接视为失败，杜绝跳到白名单外 */
	private static final HttpClient STRICT = HttpClient.newBuilder()
		.connectTimeout(Duration.ofSeconds(5))
		.followRedirects(HttpClient.Redirect.NEVER)
		.build();

	/** 受限字节响应：状态码、Content-Type、正文（超过 maxBytes 抛异常） */
	public record LimitedBytes(int status, String contentType, byte[] body) {
	}

	/**
	 * 严格模式取字节：主机先过白名单，不跟随重定向，限制大小与超时，使用部署方配置的 UA。
	 */
	public LimitedBytes getBytesStrict(URI uri, Predicate<String> hostOk, String userAgent, int maxBytes)
		throws Exception {
		assertHost(uri, hostOk);
		HttpRequest req = HttpRequest.newBuilder(uri)
			.timeout(TIMEOUT)
			.header("User-Agent", userAgent == null || userAgent.isBlank() ? "mugsun-boot-gis" : userAgent)
			.header("Accept", "image/png,image/jpeg,image/webp,image/*;q=0.8")
			.GET()
			.build();
		HttpResponse<java.io.InputStream> resp = STRICT.send(req, HttpResponse.BodyHandlers.ofInputStream());
		try (java.io.InputStream in = resp.body()) {
			long declared = resp.headers().firstValueAsLong("Content-Length").orElse(-1L);
			if (declared > maxBytes) {
				throw new ServiceException("瓦片过大");
			}
			byte[] body = in.readNBytes(maxBytes + 1);
			if (body.length > maxBytes) {
				throw new ServiceException("瓦片过大");
			}
			return new LimitedBytes(resp.statusCode(), resp.headers().firstValue("Content-Type").orElse(""), body);
		}
	}

	private JsonNode parseJson(HttpResponse<String> resp) throws Exception {
		if (resp.statusCode() < 200 || resp.statusCode() >= 300) {
			throw new ServiceException(GisConstants.MSG_SEARCH_UPSTREAM);
		}
		String body = resp.body() == null ? "" : resp.body().trim();
		if (body.isEmpty() || body.startsWith("<")) {
			throw new ServiceException(GisConstants.MSG_SEARCH_UPSTREAM);
		}
		return objectMapper.readTree(body);
	}

	private static void assertHost(URI uri, Predicate<String> hostOk) {
		String host = uri.getHost() == null ? "" : uri.getHost().toLowerCase(Locale.ROOT);
		if (!hostOk.test(host)) {
			throw new ServiceException("非法上游地址");
		}
	}
}
