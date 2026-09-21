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
 * 上游 HTTP：8s 超时、浏览器 UA；JSON / 字节 / POST JSON。
 */
@Component
public class GisUpstreamHttp {

	private static final Duration TIMEOUT = Duration.ofSeconds(8);
	private static final HttpClient HTTP = HttpClient.newBuilder()
		.connectTimeout(TIMEOUT)
		.followRedirects(HttpClient.Redirect.NORMAL)
		.build();

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
