package com.mugsun.boot.ai;

import com.mugsun.boot.common.i18n.AbstractI18nBundleTest;

/** AI 模块自己的英文翻译文件 */
class AiI18nBundleTest extends AbstractI18nBundleTest {

	@Override
	protected String file() {
		return "mugsun-boot-ai_en.properties";
	}

	@Override
	protected Class<?> anchor() {
		return AiConstants.class;
	}
}
