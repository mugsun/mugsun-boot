package com.mugsun.boot.gis;

import com.mugsun.boot.common.i18n.AbstractI18nBundleTest;

/** 地图模块自己的英文翻译文件 */
class GisI18nBundleTest extends AbstractI18nBundleTest {

	@Override
	protected String file() {
		return "mugsun-boot-gis_en.properties";
	}

	@Override
	protected Class<?> anchor() {
		return GisConstants.class;
	}
}
