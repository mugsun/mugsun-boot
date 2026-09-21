package com.mugsun.boot.gis.engine;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mugsun.boot.gis.GisConstants;
import com.mugsun.boot.gis.GisSearchService;
import com.mugsun.core.tool.exception.ServiceException;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 引擎注册四家 code；未知 / 缺 provider 由门面 400，不跨家。
 */
class GisMapEnginesTest {

	@Test
	void registryHasFourVendorCodes() {
		GisMapEngines engines = engines();
		assertThat(engines.codes()).containsExactlyInAnyOrder(
			GisConstants.PROVIDER_TIANDITU,
			GisConstants.PROVIDER_AMAP,
			GisConstants.PROVIDER_BAIDU,
			GisConstants.PROVIDER_GOOGLE);
		assertThat(engines.require(GisConstants.PROVIDER_AMAP).code()).isEqualTo("amap");
		assertThatThrownBy(() -> engines.require("not-a-vendor"))
			.isInstanceOf(ServiceException.class)
			.hasMessageContaining("未知");
	}

	@Test
	void searchRejectsUnknownAndMissingProvider() {
		GisSearchService service = new GisSearchService(null, engines());
		assertThatThrownBy(() -> service.search("天安门广场", null, null, "not-a-vendor"))
			.isInstanceOf(ServiceException.class)
			.hasMessage(GisConstants.MSG_PROVIDER_UNKNOWN);
		assertThatThrownBy(() -> service.search("天安门广场", null, null, null))
			.isInstanceOf(ServiceException.class)
			.hasMessage(GisConstants.MSG_SEARCH_PROVIDER);
		assertThatThrownBy(() -> service.search("天安门广场", null, null, "  "))
			.isInstanceOf(ServiceException.class)
			.hasMessage(GisConstants.MSG_SEARCH_PROVIDER);
		assertThatThrownBy(() -> service.reverse(116.4, 39.9, "not-a-vendor"))
			.isInstanceOf(ServiceException.class)
			.hasMessage(GisConstants.MSG_PROVIDER_UNKNOWN);
	}

	private static GisMapEngines engines() {
		GisUpstreamHttp http = new GisUpstreamHttp(new ObjectMapper());
		return new GisMapEngines(List.of(
			new TiandituEngine(http),
			new AmapEngine(http),
			new BaiduEngine(http),
			new GoogleEngine(http)));
	}
}
