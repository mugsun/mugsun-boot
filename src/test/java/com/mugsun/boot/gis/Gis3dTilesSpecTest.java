package com.mugsun.boot.gis;

import com.mugsun.core.tool.exception.ServiceException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 三维切片图层契约：内置示例走 hosted:，外部数据必须是指向清单的 http(s) 链接。
 */
class Gis3dTilesSpecTest {

	@Test
	@DisplayName("只认 3dtiles 这一种 kind")
	void recognizesKind() {
		assertThat(Gis3dTilesSpec.is3dTiles("3dtiles")).isTrue();
		assertThat(Gis3dTilesSpec.is3dTiles("xyz")).isFalse();
		assertThat(Gis3dTilesSpec.is3dTiles(null)).isFalse();
	}

	@Test
	@DisplayName("内置切片带出 hosted code，供前端拼托管地址")
	void normalizesHosted() {
		Map<String, Object> spec = Gis3dTilesSpec.normalize(Map.of("url", "hosted:demo-city"));

		assertThat(spec).containsEntry("type", "3DTILES")
			.containsEntry("url", "hosted:demo-city")
			.containsEntry("hosted", "demo-city");
	}

	@Test
	@DisplayName("纯字符串 payload 也当 url 收")
	void acceptsPlainString() {
		Map<String, Object> spec = Gis3dTilesSpec.normalize("https://cdn.example.com/city/tileset.json");

		assertThat(spec).containsEntry("url", "https://cdn.example.com/city/tileset.json");
		assertThat(spec).doesNotContainKey("hosted");
	}

	@Test
	@DisplayName("不存在的内置 code 拒收，避免图层指向空目录")
	void rejectsUnknownHosted() {
		assertThatThrownBy(() -> Gis3dTilesSpec.normalize(Map.of("url", "hosted:not-exists")))
			.isInstanceOf(ServiceException.class)
			.hasMessage(GisConstants.MSG_TILESET_MISSING);
	}

	@Test
	@DisplayName("非 http(s) 或不指向清单的地址拒收")
	void rejectsBadUrl() {
		assertThatThrownBy(() -> Gis3dTilesSpec.normalize(Map.of("url", "ftp://x/tileset.json")))
			.isInstanceOf(ServiceException.class)
			.hasMessage(GisConstants.MSG_TILESET_URL);
		assertThatThrownBy(() -> Gis3dTilesSpec.normalize(Map.of("url", "https://cdn.example.com/city/")))
			.isInstanceOf(ServiceException.class)
			.hasMessage(GisConstants.MSG_TILESET_URL);
		assertThatThrownBy(() -> Gis3dTilesSpec.normalize(Map.of()))
			.isInstanceOf(ServiceException.class)
			.hasMessage(GisConstants.MSG_TILESET_URL);
	}

	@Test
	@DisplayName("屏幕空间误差与高程偏移收敛到安全区间")
	void clampsRenderParams() {
		Map<String, Object> low = Gis3dTilesSpec.normalize(
			Map.of("url", "hosted:demo-city", "maximumScreenSpaceError", 0.01, "heightOffset", -99999));
		Map<String, Object> high = Gis3dTilesSpec.normalize(
			Map.of("url", "hosted:demo-city", "maximumScreenSpaceError", 999, "heightOffset", 99999));

		assertThat(low).containsEntry("maximumScreenSpaceError", 1d).containsEntry("heightOffset", -5000d);
		assertThat(high).containsEntry("maximumScreenSpaceError", 64d).containsEntry("heightOffset", 5000d);
	}

	@Test
	@DisplayName("参数写成字符串或写坏都不炸，落到默认值")
	void tolerantParams() {
		Map<String, Object> spec = Gis3dTilesSpec.normalize(
			Map.of("url", "hosted:demo-city", "maximumScreenSpaceError", "8", "heightOffset", "abc"));

		assertThat(spec).containsEntry("maximumScreenSpaceError", 8d).containsEntry("heightOffset", 0d);
	}
}
