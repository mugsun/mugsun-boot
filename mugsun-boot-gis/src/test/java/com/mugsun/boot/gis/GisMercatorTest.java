package com.mugsun.boot.gis;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * Web Mercator ↔ WGS84：缓冲/面积/长度运算前的平面投影，须在赤道与极区边界上行为正确。
 */
class GisMercatorTest {

	@Test
	@DisplayName("原点 (0,0) 往返近似为 0（浮点噪声可忽略）")
	void originStaysOrigin() {
		double[] m = GisMercator.to3857(0, 0);
		assertThat(m[0]).isCloseTo(0d, within(1e-9));
		assertThat(m[1]).isCloseTo(0d, within(1e-9));
		double[] back = GisMercator.to4326(0, 0);
		assertThat(back[0]).isCloseTo(0d, within(1e-9));
		assertThat(back[1]).isCloseTo(0d, within(1e-9));
	}

	@Test
	@DisplayName("赤道上 1° 经度约 111319 米")
	void oneDegreeLongitudeNearEquator() {
		double[] p = GisMercator.to3857(1, 0);
		assertThat(p[0]).isCloseTo(111319.49079327357d, within(1e-6));
		assertThat(p[1]).isCloseTo(0d, within(1e-9));
	}

	@Test
	@DisplayName("往返误差在经纬度 1e-9 以内")
	void roundTrip() {
		double lon = 116.391358;
		double lat = 39.904966;
		double[] m = GisMercator.to3857(lon, lat);
		double[] back = GisMercator.to4326(m[0], m[1]);
		assertThat(back[0]).isCloseTo(lon, within(1e-9));
		assertThat(back[1]).isCloseTo(lat, within(1e-9));
	}

	@Test
	@DisplayName("纬度被夹到 ±85.05112878，避免极点投影发散")
	void latitudeIsClampedNearPoles() {
		double[] north = GisMercator.to3857(0, 90);
		double[] clamp = GisMercator.to3857(0, 85.05112878);
		assertThat(north[1]).isCloseTo(clamp[1], within(1e-6));

		double[] south = GisMercator.to3857(0, -90);
		double[] clampS = GisMercator.to3857(0, -85.05112878);
		assertThat(south[1]).isCloseTo(clampS[1], within(1e-6));
	}

	@Test
	@DisplayName("东半球 x>0，北半球 y>0")
	void quadrantSigns() {
		double[] p = GisMercator.to3857(120, 30);
		assertThat(p[0]).isPositive();
		assertThat(p[1]).isPositive();
		double[] sw = GisMercator.to3857(-120, -30);
		assertThat(sw[0]).isNegative();
		assertThat(sw[1]).isNegative();
	}
}
