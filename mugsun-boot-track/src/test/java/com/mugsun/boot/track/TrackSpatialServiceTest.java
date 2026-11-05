package com.mugsun.boot.track;

import com.mugsun.boot.common.constant.TrackConstants;
import com.mugsun.core.tool.exception.ServiceException;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 空间行为：zoom→p / bbox 校验（无 Spring）。 */
class TrackSpatialServiceTest {

	@Test
	void spatialDaysMaxAlignedWithFunnel() {
		assertThat(TrackConstants.SPATIAL_DAYS_MAX)
			.as("空间 days 上限与漏斗对齐")
			.isEqualTo(TrackConstants.FUNNEL_DAYS_MAX)
			.isEqualTo(30);
	}

	@Test
	void zoomToPrecisionTable() {
		assertThat(TrackSpatialService.resolvePrecision(null, null)).isEqualTo(2);
		assertThat(TrackSpatialService.resolvePrecision(1, null)).isEqualTo(1);
		assertThat(TrackSpatialService.resolvePrecision(5, null)).isEqualTo(1);
		assertThat(TrackSpatialService.resolvePrecision(6, null)).isEqualTo(2);
		assertThat(TrackSpatialService.resolvePrecision(8, null)).isEqualTo(2);
		assertThat(TrackSpatialService.resolvePrecision(9, null)).isEqualTo(3);
		assertThat(TrackSpatialService.resolvePrecision(11, null)).isEqualTo(3);
		assertThat(TrackSpatialService.resolvePrecision(12, null)).isEqualTo(4);
		assertThat(TrackSpatialService.resolvePrecision(20, null)).isEqualTo(4);
		assertThat(TrackSpatialService.resolvePrecision(20, 1)).isEqualTo(1);
		assertThat(TrackSpatialService.resolvePrecision(null, 3)).isEqualTo(3);
	}

	@Test
	void zoomAndPrecisionRejectOutOfRange() {
		assertThatThrownBy(() -> TrackSpatialService.resolvePrecision(0, null))
			.isInstanceOf(ServiceException.class);
		assertThatThrownBy(() -> TrackSpatialService.resolvePrecision(21, null))
			.isInstanceOf(ServiceException.class);
		assertThatThrownBy(() -> TrackSpatialService.resolvePrecision(10, 0))
			.isInstanceOf(ServiceException.class);
		assertThatThrownBy(() -> TrackSpatialService.resolvePrecision(10, 5))
			.isInstanceOf(ServiceException.class);
	}

	@Test
	void bboxParseAndReject() {
		assertThat(TrackSpatialService.parseBbox(null)).isNull();
		assertThat(TrackSpatialService.parseBbox("  ")).isNull();
		double[] box = TrackSpatialService.parseBbox("116.3,39.8,116.5,40.0");
		assertThat(box).containsExactly(116.3, 39.8, 116.5, 40.0);

		assertThatThrownBy(() -> TrackSpatialService.parseBbox("1,2,3"))
			.isInstanceOf(ServiceException.class);
		assertThatThrownBy(() -> TrackSpatialService.parseBbox("a,b,c,d"))
			.isInstanceOf(ServiceException.class);
		assertThatThrownBy(() -> TrackSpatialService.parseBbox("170,10,-170,20"))
			.as("跨 180°").isInstanceOf(ServiceException.class)
			.hasMessageContaining("180");
		assertThatThrownBy(() -> TrackSpatialService.parseBbox("10,40,20,30"))
			.as("纬度颠倒").isInstanceOf(ServiceException.class);
		assertThatThrownBy(() -> TrackSpatialService.parseBbox("200,0,210,1"))
			.isInstanceOf(ServiceException.class);
	}
}
