package com.mugsun.boot.common.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mugsun.boot.captcha.CaptchaBodyLimitFilter;
import com.mugsun.boot.security.EncodedPathRejectFilter;
import com.mugsun.core.i18n.locale.LocaleNegotiator;
import com.mugsun.core.i18n.message.I18n;
import com.mugsun.core.i18n.message.MessageTranslator;
import com.mugsun.core.i18n.source.MessageBundles;
import com.mugsun.core.i18n.source.MugsunMessageSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.nio.charset.StandardCharsets;
import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 过滤器、拦截器里自己写响应的地方，提示语跟着请求的语言走。
 * 这里不起整个应用，把翻译按应用启动后的样子装上：读类路径上所有翻译文件，默认简体中文，另外支持翻译文件里出现的语言。
 */
class DirectResponseTest {

	private final ObjectMapper objectMapper = new ObjectMapper();

	private MessageTranslator translator;

	@BeforeEach
	void installTranslations() {
		MessageBundles bundles = MessageBundles.load(new PathMatchingResourcePatternResolver(), null);
		translator = new MessageTranslator(new MugsunMessageSource(bundles),
			new LocaleNegotiator(Locale.SIMPLIFIED_CHINESE, bundles.locales()));
		I18n.install(translator);
		LocaleContextHolder.resetLocaleContext();
	}

	@AfterEach
	void removeTranslations() {
		LocaleContextHolder.resetLocaleContext();
		I18n.uninstall(translator);
	}

	@Test
	void withoutCurrentLocale_followsTheAcceptLanguageHeader() {
		assertThat(DirectResponse.msg(request("en-US,en;q=0.9"), "请求体过大")).isEqualTo("Request body too large");
		assertThat(DirectResponse.msg(request("zh-CN,zh;q=0.9,en;q=0.8"), "请求体过大")).isEqualTo("请求体过大");
		assertThat(DirectResponse.msg(request(null), "请求体过大")).isEqualTo("请求体过大");
	}

	@Test
	void withCurrentLocale_usesItInsteadOfTheHeader() {
		LocaleContextHolder.setLocale(Locale.ENGLISH);
		assertThat(DirectResponse.msg(request(null), "请求体过大")).isEqualTo("Request body too large");

		LocaleContextHolder.setLocale(Locale.SIMPLIFIED_CHINESE);
		assertThat(DirectResponse.msg(request("en"), "请求体过大")).isEqualTo("请求体过大");
	}

	@Test
	void sentenceWithoutTranslation_staysAsItIs() {
		assertThat(DirectResponse.msg(request("en"), "这一句没有配翻译")).isEqualTo("这一句没有配翻译");
	}

	@Test
	void messagesFromMugsunCoreAreTranslatedToo() {
		assertThat(DirectResponse.msg(request("en"), "请求未授权")).isEqualTo("Unauthorized");
	}

	@Test
	void valuesAreFilledIn() {
		assertThat(DirectResponse.msg(request(null), "缺少授权范围：{0}", "user:read"))
			.isEqualTo("缺少授权范围：user:read");
		assertThat(DirectResponse.msg(request("en"), "缺少授权范围：{0}", "user:read"))
			.isEqualTo("Missing scope: user:read");
	}

	@Test
	void jsonMsg_canBePutIntoHandWrittenJson() throws Exception {
		String text = "带\"引号\"、\\反斜杠和\n换行";

		String json = "{\"msg\":\"" + DirectResponse.jsonMsg(request(null), text) + "\"}";

		assertThat(objectMapper.readTree(json).path("msg").asText()).isEqualTo(text);
	}

	/** 排在最前面的安全检查：这时还没有「当前语言」，按请求头定 */
	@Test
	void encodedPathFilter_answersInTheRequestLanguage() throws Exception {
		assertThat(rejectedByEncodedPathFilter("en").path("msg").asText())
			.isEqualTo("The request path contains illegal encoding");
		assertThat(rejectedByEncodedPathFilter(null).path("msg").asText()).isEqualTo("请求路径含非法编码");
	}

	/** 排在后面的过滤器：前面已经定好了「当前语言」，直接用 */
	@Test
	void captchaBodyLimitFilter_answersInTheCurrentLanguage() throws Exception {
		LocaleContextHolder.setLocale(Locale.ENGLISH);
		MockHttpServletRequest request = new MockHttpServletRequest("POST", "/auth/captcha/verify");
		request.setContent(new byte[64 * 1024]);
		MockHttpServletResponse response = new MockHttpServletResponse();

		new CaptchaBodyLimitFilter().doFilter(request, response, new MockFilterChain());

		assertThat(body(response).path("msg").asText()).isEqualTo("Verification failed. Please try again.");
	}

	private JsonNode rejectedByEncodedPathFilter(String acceptLanguage) throws Exception {
		MockHttpServletRequest request = new MockHttpServletRequest("GET", "/system/%74enant/list");
		if (acceptLanguage != null) {
			request.addHeader("Accept-Language", acceptLanguage);
		}
		MockHttpServletResponse response = new MockHttpServletResponse();
		new EncodedPathRejectFilter().doFilter(request, response, new MockFilterChain());
		return body(response);
	}

	private JsonNode body(MockHttpServletResponse response) throws Exception {
		return objectMapper.readTree(response.getContentAsString(StandardCharsets.UTF_8));
	}

	private static MockHttpServletRequest request(String acceptLanguage) {
		MockHttpServletRequest request = new MockHttpServletRequest("GET", "/any");
		if (acceptLanguage != null) {
			request.addHeader("Accept-Language", acceptLanguage);
		}
		return request;
	}
}
