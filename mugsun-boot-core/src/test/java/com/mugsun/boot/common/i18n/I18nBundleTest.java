package com.mugsun.boot.common.i18n;

import com.mugsun.boot.common.web.DirectResponse;

/** mugsun-boot-core 自己的英文翻译文件 */
class I18nBundleTest extends AbstractI18nBundleTest {

	@Override
	protected String file() {
		return "mugsun-boot_en.properties";
	}

	@Override
	protected Class<?> anchor() {
		return DirectResponse.class;
	}
}
