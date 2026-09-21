package com.mugsun.boot.gis;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * 高德 GCJ-02 与 WGS84 互转：北京城区应有偏移，往返误差在米级。
 */
class GisChinaCrsTest {

	@Test
	void beijingRoundTripAndOffset() {
		double wgsLon = 116.391358;
		double wgsLat = 39.904966;
		double[] gcj = GisChinaCrs.toGcj02(wgsLon, wgsLat);
		assertThat(Math.hypot(gcj[0] - wgsLon, gcj[1] - wgsLat)).isGreaterThan(0.001d);
		double[] back = GisChinaCrs.toWgs84(gcj[0], gcj[1]);
		assertThat(back[0]).isCloseTo(wgsLon, within(1e-5));
		assertThat(back[1]).isCloseTo(wgsLat, within(1e-5));
	}

	@Test
	void outsideChinaUnchanged() {
		double[] gcj = GisChinaCrs.toGcj02(0, 0);
		assertThat(gcj).containsExactly(0d, 0d);
		assertThat(GisChinaCrs.toWgs84(10, 10)).containsExactly(10d, 10d);
	}

	@Test
	void beijingBd09RoundTrip() {
		double wgsLon = 116.391358;
		double wgsLat = 39.904966;
		double[] bd = GisChinaCrs.toBd09(wgsLon, wgsLat);
		assertThat(Math.hypot(bd[0] - wgsLon, bd[1] - wgsLat)).isGreaterThan(0.002d);
		double[] back = GisChinaCrs.bd09ToWgs84(bd[0], bd[1]);
		assertThat(back[0]).isCloseTo(wgsLon, within(1e-5));
		assertThat(back[1]).isCloseTo(wgsLat, within(1e-5));
	}

	@Test
	void chinaBorderInsideIsTransformed() {
		// 紧贴国境内侧（黑龙江附近）应发生偏移
		double[] gcj = GisChinaCrs.toGcj02(130.0, 45.0);
		assertThat(Math.hypot(gcj[0] - 130.0, gcj[1] - 45.0)).isGreaterThan(1e-4);
	}

	@Test
	void chinaBorderOutsideUnchanged() {
		assertThat(GisChinaCrs.outOfChina(70.0, 40.0)).isTrue();
		assertThat(GisChinaCrs.toGcj02(70.0, 40.0)).containsExactly(70.0, 40.0);
		assertThat(GisChinaCrs.outOfChina(140.0, 40.0)).isTrue();
		assertThat(GisChinaCrs.outOfChina(116.0, 0.5)).isTrue();
		assertThat(GisChinaCrs.outOfChina(116.0, 56.0)).isTrue();
	}

	@Test
	void shanghaiAndUrumqiBothOffset() {
		double[] sh = GisChinaCrs.toGcj02(121.4737, 31.2304);
		double[] ur = GisChinaCrs.toGcj02(87.6168, 43.8256);
		assertThat(Math.hypot(sh[0] - 121.4737, sh[1] - 31.2304)).isGreaterThan(0.001d);
		assertThat(Math.hypot(ur[0] - 87.6168, ur[1] - 43.8256)).isGreaterThan(0.001d);
	}
}
