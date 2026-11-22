package com.mugsun.boot;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;

import java.net.URI;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 多语言回归：真实 HTTP 请求，提示语跟着请求头 Accept-Language 走，没带或者带的语言没有翻译时是简体中文。
 * 提示语有四条路出去，每条各测一个：控制器抛的业务异常、统一异常处理转出来的、过滤器里自己写的、
 * 排在最前面（比「定语言」还早）的过滤器里自己写的。
 */
class I18nApiTest extends AbstractIntegrationTest {

	private static final String ENGLISH = "en-US,en;q=0.9";
	private static final String CHINESE = "zh-CN,zh;q=0.9,en;q=0.8";

	@Test
	void businessMessageFollowsTheRequestLanguage() {
		String token = loginAdmin();
		String url = "/system/tenant-package/detail?id=999999999";

		assertThat(msg(url, token, ENGLISH))
			.isEqualTo("This plan does not exist. Please refresh the list and try again.");
		assertThat(msg(url, token, CHINESE)).isEqualTo("这个套餐不存在。请刷新列表后再看");
		assertThat(msg(url, token, null)).as("没带语言").isEqualTo("这个套餐不存在。请刷新列表后再看");
		assertThat(msg(url, token, "fr-FR,fr;q=0.9")).as("带的语言没有翻译").isEqualTo("这个套餐不存在。请刷新列表后再看");
	}

	/** 未登录：提示语和它的英文都在 mugsun-core 里 */
	@Test
	void frameworkMessageFollowsTheRequestLanguage() {
		assertThat(msg("/auth/info", null, ENGLISH)).isEqualTo("Unauthorized");
		assertThat(msg("/auth/info", null, null)).isEqualTo("请求未授权");
	}

	/** 监控端点鉴权是过滤器，自己写响应，不经过统一返回体的翻译 */
	@Test
	void filterMessageFollowsTheRequestLanguage() {
		assertThat(msg("/actuator/metrics", null, ENGLISH)).isEqualTo("Unauthorized");
		assertThat(msg("/actuator/metrics", null, null)).isEqualTo("请求未授权");
	}

	/** 路径编码检查排在所有过滤器前面，这时还没有「当前语言」 */
	@Test
	void earliestFilterMessageFollowsTheRequestLanguage() {
		// 用 URI 而不是字符串：字符串会被当成模板，%74 会再编码一次
		URI uri = URI.create("http://localhost:" + port + "/system/%74enant/list");

		assertThat(msg(uri, ENGLISH)).isEqualTo("The request path contains illegal encoding");
		assertThat(msg(uri, null)).isEqualTo("请求路径含非法编码");
	}

	private String msg(String url, String token, String acceptLanguage) {
		return msgOf(rest.exchange(url, HttpMethod.GET, new HttpEntity<>(headers(token, acceptLanguage)), String.class));
	}

	private String msg(URI uri, String acceptLanguage) {
		return msgOf(rest.exchange(uri, HttpMethod.GET, new HttpEntity<>(headers(null, acceptLanguage)), String.class));
	}

	private String msgOf(ResponseEntity<String> response) {
		return readBody(response).path("msg").asText();
	}

	private HttpHeaders headers(String token, String acceptLanguage) {
		HttpHeaders headers = authHeaders(token);
		if (acceptLanguage != null) {
			headers.set(HttpHeaders.ACCEPT_LANGUAGE, acceptLanguage);
		}
		return headers;
	}
}
