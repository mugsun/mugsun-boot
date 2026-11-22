package com.mugsun.boot.common.i18n;

import com.mugsun.core.i18n.message.I18n;
import com.mugsun.core.i18n.message.MessageTranslator;
import com.mugsun.core.i18n.source.MugsunMessageSource;
import org.springframework.context.i18n.LocaleContextHolder;

import java.util.Locale;

/**
 * 单元测试里临时当成「英文请求」：译文用类路径上各模块真实的翻译文件，系统默认语言还是中文。
 * 用 try-with-resources 包住；出来以后回到没有装配的状态（一律返回中文原文）。
 */
public final class EnglishRequest implements AutoCloseable {

	private final MessageTranslator translator;

	private EnglishRequest(MessageTranslator translator) {
		this.translator = translator;
	}

	public static EnglishRequest open() {
		MessageTranslator translator = new MessageTranslator(
			new MugsunMessageSource(AbstractI18nBundleTest.load(name -> name.startsWith("mugsun-boot"))),
			Locale.SIMPLIFIED_CHINESE);
		I18n.install(translator);
		LocaleContextHolder.setLocale(Locale.ENGLISH);
		return new EnglishRequest(translator);
	}

	@Override
	public void close() {
		LocaleContextHolder.resetLocaleContext();
		I18n.uninstall(translator);
	}
}
