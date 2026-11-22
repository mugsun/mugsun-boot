package com.mugsun.boot.common.web;

import com.fasterxml.jackson.core.io.JsonStringEncoder;
import com.mugsun.core.i18n.message.I18n;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.context.i18n.LocaleContext;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.http.HttpHeaders;

import java.util.Locale;

/**
 * 过滤器、拦截器里自己写响应的地方用：把提示语翻译成这次请求的语言。
 * <p>控制器返回的 {@code R}、抛出的业务异常不用管，返回前会统一翻译；自己往 response 里写 JSON 的地方不经过那一步，
 * 要在写之前调这里。翻译文件（{@code i18n/mugsun-boot_en.properties}）里没有这句时原样返回。
 * <p>语言怎么定：已经有「当前语言」就用它——mugsun-core 的 LocaleContextFilter 排在 HIGHEST_PRECEDENCE + 5，
 * 它后面的过滤器和拦截器里都有；比它还靠前的过滤器（MultipartAuthFilter、EncodedPathRejectFilter）里还没有，
 * 直接按请求头 Accept-Language 定。所以排在哪个位置的过滤器都可以用，不用关心顺序。
 */
public final class DirectResponse {

	private DirectResponse() {
	}

	/**
	 * @param text 提示语的中文原文；带变量时变量的位置写 {@code {0}}、{@code {1}}
	 * @param args 填到占位里的值；数字会按语言加千分位，不想加的传字符串
	 */
	public static String msg(HttpServletRequest request, String text, Object... args) {
		Locale locale = locale(request);
		return args == null || args.length == 0 ? I18n.translate(locale, text) : I18n.get(locale, text, args);
	}

	/** 同 {@link #msg}，再转义成可以直接拼进 JSON 字符串里的写法，给手写 JSON 的地方用 */
	public static String jsonMsg(HttpServletRequest request, String text) {
		String translated = msg(request, text);
		return translated == null ? "" : new String(JsonStringEncoder.getInstance().quoteAsString(translated));
	}

	private static Locale locale(HttpServletRequest request) {
		LocaleContext context = LocaleContextHolder.getLocaleContext();
		Locale current = context != null ? context.getLocale() : null;
		return current != null ? current : I18n.negotiate(request.getHeader(HttpHeaders.ACCEPT_LANGUAGE));
	}
}
