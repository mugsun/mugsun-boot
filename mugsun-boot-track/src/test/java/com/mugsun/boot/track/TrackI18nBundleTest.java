package com.mugsun.boot.track;

import com.mugsun.boot.common.i18n.AbstractI18nBundleTest;

/** 埋点分析模块自己的英文翻译文件 */
class TrackI18nBundleTest extends AbstractI18nBundleTest {

	@Override
	protected String file() {
		return "mugsun-boot-track_en.properties";
	}

	@Override
	protected Class<?> anchor() {
		return TrackAutoConfiguration.class;
	}
}
