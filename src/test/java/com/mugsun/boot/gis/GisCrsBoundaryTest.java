package com.mugsun.boot.gis;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * 坐标系的边界与极值行为。{@link GisChinaCrsTest} 与 {@link GisMercatorTest} 测的是
 * 「常规点算得对不对」，这里专门测<b>边界</b>：国境线两侧、界上取值、全国范围的误差上界、
 * 反经线、极点、以及非法输入。
 *
 * <p>本类的由来：G107 审计把「坐标系纠偏正确」判为**部分 PASS**——原用例只有北京单点往返，
 * 而单点通过说明不了全域精度。补测后确实抓到实质问题：反向转换原用「二倍点减正向」一步近似，
 * 在全国 0.5° 网格上最大往返误差 <b>6.79 米</b>（最差点在 129°E/53°N 一带），
 * 改迭代逼近后降到亚毫米。下面的网格断言就是防止它退回一步近似。
 */
class GisCrsBoundaryTest {

	/** 覆盖全国的采样网格：0.5° 步进，约 1.2 万个点 */
	private static final double LON_FROM = 73;
	private static final double LON_TO = 135;
	private static final double LAT_FROM = 4;
	private static final double LAT_TO = 53;
	private static final double STEP = 0.5;

	// ==================== 全域精度上界 ====================

	@Test
	@DisplayName("GCJ-02 往返：全国网格最大误差 ≤ 1e-7 度（守住迭代逼近，防退回一步近似）")
	void gcjRoundTripAccurateAcrossChina() {
		double worst = 0;
		double worstLon = 0;
		double worstLat = 0;
		for (double lon = LON_FROM; lon <= LON_TO; lon += STEP) {
			for (double lat = LAT_FROM; lat <= LAT_TO; lat += STEP) {
				double[] gcj = GisChinaCrs.toGcj02(lon, lat);
				double[] back = GisChinaCrs.toWgs84(gcj[0], gcj[1]);
				double err = Math.hypot(back[0] - lon, back[1] - lat);
				if (err > worst) {
					worst = err;
					worstLon = lon;
					worstLat = lat;
				}
			}
		}
		assertThat(worst)
			.describedAs("最差点 %.1f,%.1f 往返误差 %.3e 度（约 %.2f 米）", worstLon, worstLat, worst,
				worst * 111320)
			.isLessThan(1e-7);
	}

	@Test
	@DisplayName("BD-09 往返：全国网格最大误差 ≤ 1e-5 度（约 1 米）")
	void bd09RoundTripAcrossChina() {
		// 实测 1.9e-6 度（约 0.21 米）。这点残差来自 BD-09 ↔ GCJ-02 那对公式本身
		// （z/theta 形式并非严格互逆），与上面的迭代无关；0.21 米对地图展示无影响，如实留着。
		double worst = 0;
		for (double lon = LON_FROM; lon <= LON_TO; lon += STEP) {
			for (double lat = LAT_FROM; lat <= LAT_TO; lat += STEP) {
				double[] bd = GisChinaCrs.toBd09(lon, lat);
				double[] back = GisChinaCrs.bd09ToWgs84(bd[0], bd[1]);
				worst = Math.max(worst, Math.hypot(back[0] - lon, back[1] - lat));
			}
		}
		assertThat(worst).isLessThan(1e-5);
	}

	// ==================== 国境线：跨界与界上 ====================

	@Test
	@DisplayName("国境线两侧存在约 470 米跳变——GCJ-02 的固有特性，不是缺陷")
	void borderIsDiscontinuousByDesign() {
		// 偏移只在境内适用，界外原样返回，因此界上必然不连续：
		// 相距 2 微度的两点，转换后被拉开数百米。锁住它是为了让后人知道这是已知行为，
		// 别当 bug「修」掉（真要连续就得在边境带做羽化，那会引入别的失真）。
		double[] inside = GisChinaCrs.toGcj02(72.004 + 1e-6, 40);
		double[] outside = GisChinaCrs.toGcj02(72.004 - 1e-6, 40);
		double gapMeters = Math.hypot(inside[0] - outside[0], inside[1] - outside[1]) * 111320;
		assertThat(gapMeters).isBetween(300d, 700d);
	}

	@Test
	@DisplayName("界上取值算「境内」：四条边界线上的点都要参与偏移")
	void exactBorderValuesCountAsInside() {
		assertThat(GisChinaCrs.outOfChina(72.004, 40)).isFalse();
		assertThat(GisChinaCrs.outOfChina(137.8347, 40)).isFalse();
		assertThat(GisChinaCrs.outOfChina(116, 0.8293)).isFalse();
		assertThat(GisChinaCrs.outOfChina(116, 55.8271)).isFalse();
		// 越界一丝即为境外
		assertThat(GisChinaCrs.outOfChina(72.004 - 1e-9, 40)).isTrue();
		assertThat(GisChinaCrs.outOfChina(137.8347 + 1e-9, 40)).isTrue();
		assertThat(GisChinaCrs.outOfChina(116, 0.8293 - 1e-9)).isTrue();
		assertThat(GisChinaCrs.outOfChina(116, 55.8271 + 1e-9)).isTrue();
	}

	@Test
	@DisplayName("跨界扫描：只有跨过界线那一步出现跳变，境内段本身是连续的")
	void onlyTheBorderStepJumps() {
		double lat = 40;
		double prevLon = 71.0;
		double[] prev = GisChinaCrs.toGcj02(prevLon, lat);
		int jumps = 0;
		for (double lon = 71.05; lon <= 73.0; lon += 0.05) {
			double[] cur = GisChinaCrs.toGcj02(lon, lat);
			// 扣掉本身推进的 0.05°，剩下的就是偏移量的变化
			double delta = Math.abs(Math.hypot(cur[0] - prev[0], cur[1] - prev[1]) - (lon - prevLon));
			if (delta > 1e-3) {
				jumps++;
			}
			prev = cur;
			prevLon = lon;
		}
		assertThat(jumps).isEqualTo(1);
	}

	// ==================== 极值区域 ====================

	@Test
	@DisplayName("东西南北四端极值点：境内则必有偏移，境外则原样")
	void extremePointsOfChina() {
		// 帕米尔（最西）、黑龙江抚远（最东）、漠河（最北）都在偏移范围内
		for (double[] p : new double[][] { { 73.5, 39.5 }, { 134.7, 48.4 }, { 122.4, 53.5 } }) {
			double[] gcj = GisChinaCrs.toGcj02(p[0], p[1]);
			assertThat(Math.hypot(gcj[0] - p[0], gcj[1] - p[1]))
				.describedAs("极值点 %s 应发生偏移", java.util.Arrays.toString(p))
				.isGreaterThan(1e-4);
		}
		// 三沙（南沙群岛，纬度低于 0.8293 的部分落在适用范围外）
		assertThat(GisChinaCrs.outOfChina(112.3, 0.5)).isTrue();
		assertThat(GisChinaCrs.toGcj02(112.3, 0.5)).containsExactly(112.3, 0.5);
	}

	@Test
	@DisplayName("港澳台在偏移范围内（高德底图同样带偏移）")
	void hkMacaoTaiwanAreOffset() {
		for (double[] p : new double[][] { { 114.1694, 22.3193 }, { 113.5439, 22.1987 },
			{ 121.5654, 25.0330 } }) {
			double[] gcj = GisChinaCrs.toGcj02(p[0], p[1]);
			assertThat(Math.hypot(gcj[0] - p[0], gcj[1] - p[1])).isGreaterThan(1e-4);
			double[] back = GisChinaCrs.toWgs84(gcj[0], gcj[1]);
			assertThat(back[0]).isCloseTo(p[0], within(1e-7));
			assertThat(back[1]).isCloseTo(p[1], within(1e-7));
		}
	}

	// ==================== 非法输入 ====================

	@Test
	@DisplayName("非数输入按境外处置：原样返回、不抛异常、不产生虚假偏移")
	void nonFiniteInputPassesThroughUntouched() {
		for (double bad : new double[] { Double.NaN, Double.POSITIVE_INFINITY,
			Double.NEGATIVE_INFINITY }) {
			assertThat(GisChinaCrs.outOfChina(bad, 40)).isTrue();
			assertThat(GisChinaCrs.outOfChina(116, bad)).isTrue();
			// 原样返回：偏移公式拿到 NaN 只会算出 NaN 再一路带下去，短路更容易在上游发现
			assertSameBits(GisChinaCrs.toGcj02(bad, 40)[0], bad);
			assertSameBits(GisChinaCrs.toWgs84(bad, 40)[0], bad);
			assertSameBits(GisChinaCrs.toBd09(bad, 40)[0], bad);
			assertSameBits(GisChinaCrs.bd09ToWgs84(bad, 40)[0], bad);
		}
	}

	/** NaN 不能用 == 比（NaN != NaN），按 {@link Double#compare} 判等可同时覆盖 NaN 与两个无穷 */
	private static void assertSameBits(double actual, double expected) {
		assertThat(Double.compare(actual, expected))
			.describedAs("期望原样返回 %s，实际 %s", expected, actual)
			.isZero();
	}

	@Test
	@DisplayName("非数输入不会让反向迭代陷入死循环或抛异常")
	void nonFiniteDoesNotHangInverseIteration() {
		// 迭代反向若不短路，NaN 会在循环里反复参与三角运算；这里确保它走的是境外分支
		assertThat(GisChinaCrs.toWgs84(Double.NaN, Double.NaN)).containsExactly(Double.NaN,
			Double.NaN);
	}

	// ==================== Web Mercator 边界 ====================

	@Test
	@DisplayName("反经线 ±180 投影对称，且为图幅半宽 ±20037508.34 米")
	void antimeridianIsSymmetric() {
		double[] east = GisMercator.to3857(180, 0);
		double[] west = GisMercator.to3857(-180, 0);
		assertThat(east[0]).isCloseTo(20037508.342789244d, within(1e-6));
		assertThat(west[0]).isCloseTo(-20037508.342789244d, within(1e-6));
		assertThat(east[0]).isCloseTo(-west[0], within(1e-9));
	}

	@Test
	@DisplayName("超出 ±180 的经度线性外推、不做回绕（投影函数不替调用方决定归一化）")
	void beyondAntimeridianExtrapolatesLinearly() {
		double[] at190 = GisMercator.to3857(190, 0);
		double[] at180 = GisMercator.to3857(180, 0);
		double[] at10 = GisMercator.to3857(10, 0);
		// 190° 落在 180° 之外，而不是被折回 -170°
		assertThat(at190[0]).isGreaterThan(at180[0]);
		assertThat(at190[0] - at180[0]).isCloseTo(at10[0], within(1e-6));
	}

	@Test
	@DisplayName("极点被夹取后仍可往返，且夹取边界内的高纬度点可逆")
	void polarClampRoundTrip() {
		double[] clamped = GisMercator.to3857(0, 90);
		double[] back = GisMercator.to4326(clamped[0], clamped[1]);
		assertThat(back[1]).isCloseTo(85.05112878d, within(1e-6));

		double lat = 85.0;
		double[] m = GisMercator.to3857(30, lat);
		double[] r = GisMercator.to4326(m[0], m[1]);
		assertThat(r[0]).isCloseTo(30d, within(1e-9));
		assertThat(r[1]).isCloseTo(lat, within(1e-9));
	}

	@Test
	@DisplayName("非数输入传导为非数，不抛异常也不静默变 0")
	void mercatorNonFiniteIsTransparent() {
		assertThat(GisMercator.to3857(0, Double.NaN)[1]).isNaN();
		assertThat(GisMercator.to3857(Double.NaN, 0)[0]).isNaN();
		assertThat(GisMercator.to4326(Double.NaN, Double.NaN)[0]).isNaN();
	}
}
