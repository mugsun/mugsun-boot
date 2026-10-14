package com.mugsun.boot.log;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class OperLogPropertiesTest {

	@Test
	void defaultsExcludeTrackSdkEndpointsOnly() {
		OperLogProperties p = new OperLogProperties();
		assertThat(p.getExcludePaths()).containsExactly("/track/collect", "/track/replay", "/track/api-body");
		assertThat(p.isExcluded("/track/api-body")).isTrue();
		assertThat(p.isExcluded("/system/track/user/api-body")).isFalse();
		assertThat(p.isExcluded("/track/collect")).isTrue();
		assertThat(p.isExcluded("/track/replay")).isTrue();
		assertThat(p.isExcluded("/track/collect/")).isTrue();
		// 管理端埋点接口仍要留痕
		assertThat(p.isExcluded("/system/track/replay/data")).isFalse();
		assertThat(p.isExcluded("/track/collect-x")).isFalse();
		assertThat(p.isExcluded("/system/user/submit")).isFalse();
		assertThat(p.isExcluded(null)).isFalse();
	}

	@Test
	void configurableAntPatternsAndEmptyList() {
		OperLogProperties p = new OperLogProperties();
		p.setExcludePaths(List.of("/open/**", " "));
		assertThat(p.isExcluded("/open/a/b")).isTrue();
		assertThat(p.isExcluded("/track/collect")).as("覆盖即整体替换").isFalse();
		p.setExcludePaths(null);
		assertThat(p.isExcluded("/track/collect")).isFalse();
	}
}
