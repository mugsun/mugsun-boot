package com.mugsun.boot.gis;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.io.WKTWriter;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Java 回落缓冲对照 PostGIS geography：同一要素、同一米数，
 * ① Java 缓冲外环每个顶点到原要素的 ST_Distance(geography) 与设定米数相差 < 1%（实测 < 0.5%）；
 * ② Java 缓冲与 ST_Buffer(geography) 的 ST_Area(geography) 相差 < 2%（PostGIS 自身也是投影近似）。
 */
class GisBufferPostgisParityTest {

	private static final GeometryFactory GF = new GeometryFactory();
	private static PostgreSQLContainer<?> postgis;
	private static JdbcTemplate jdbc;

	@BeforeAll
	static void startDb() {
		postgis = new PostgreSQLContainer<>(
			DockerImageName.parse("postgis/postgis:16-3.4").asCompatibleSubstituteFor("postgres"))
			.withDatabaseName("mugsun").withUsername("mugsun").withPassword("mugsun");
		postgis.start();
		DriverManagerDataSource ds = new DriverManagerDataSource(
			postgis.getJdbcUrl(), postgis.getUsername(), postgis.getPassword());
		ds.setDriverClassName("org.postgresql.Driver");
		jdbc = new JdbcTemplate(ds);
		jdbc.execute("CREATE EXTENSION IF NOT EXISTS postgis");
	}

	@AfterAll
	static void stopDb() {
		if (postgis != null) {
			postgis.stop();
		}
	}

	private static Geometry[] shapes(double lon, double lat) {
		double k = 1 / Math.cos(Math.toRadians(lat));
		return new Geometry[] {
			GF.createPoint(new Coordinate(lon, lat)),
			GF.createLineString(new Coordinate[] { new Coordinate(lon, lat), new Coordinate(lon + 0.03 * k, lat + 0.01),
				new Coordinate(lon + 0.04 * k, lat + 0.02) }),
			GF.createPolygon(new Coordinate[] { new Coordinate(lon, lat), new Coordinate(lon + 0.018 * k, lat),
				new Coordinate(lon + 0.018 * k, lat + 0.018), new Coordinate(lon, lat + 0.018), new Coordinate(lon, lat) }) };
	}

	@Test
	@DisplayName("北京 / 赤道 / 72°N × 点线面：Java 缓冲对照 PostGIS geography")
	void parity() {
		WKTWriter w = new WKTWriter();
		double[][] places = { { 116.4, 39.9 }, { 30.0, 0.0 }, { 25.0, 72.0 } };
		String[] kinds = { "点", "线", "面" };
		for (double[] p : places) {
			Geometry[] gs = shapes(p[0], p[1]);
			for (int i = 0; i < gs.length; i++) {
				for (double meters : new double[] { 500d, 20_000d, 200_000d }) {
					Geometry ours = GisGeodesic.buffer(gs[i], meters);
					String feature = w.write(gs[i]);
					String target = gs[i].getDimension() == 2 ? "ST_Boundary(ST_GeomFromText(?, 4326))" : "ST_GeomFromText(?, 4326)";
					Double[] range = jdbc.queryForObject(
						"SELECT min(d), max(d) FROM (SELECT ST_Distance((dp).geom::geography, " + target + "::geography) AS d"
							+ " FROM ST_DumpPoints(ST_ExteriorRing(ST_GeometryN(ST_GeomFromText(?, 4326), 1))) dp) t",
						(rs, n) -> new Double[] { rs.getDouble(1), rs.getDouble(2) }, feature, w.write(ours));
					Double[] areas = jdbc.queryForObject(
						"SELECT ST_Area(ST_GeomFromText(?, 4326)::geography),"
							+ " ST_Area(ST_Buffer(ST_GeomFromText(?, 4326)::geography, ?)::geography)",
						(rs, n) -> new Double[] { rs.getDouble(1), rs.getDouble(2) }, w.write(ours), feature, meters);
					double lo = range[0] / meters - 1, hi = range[1] / meters - 1, area = areas[0] / areas[1] - 1;
					System.out.printf("PG|%.1f|%s|%.0f|%+.3f%%|%+.3f%%|area %+.3f%%%n",
						p[1], kinds[i], meters, lo * 100, hi * 100, area * 100);
					assertThat(lo).isGreaterThan(-0.01);
					assertThat(hi).isLessThan(0.01);
					assertThat(area).isBetween(-0.02, 0.02);
				}
			}
		}
	}

	@Test
	@DisplayName("极区长边多边形面积（60°×60°、高纬大跨度）对照 PostGIS ST_Area(geography)：边都按测地线，相差 < 1e-6")
	void polarAreaParity() {
		String[] polys = {
			"POLYGON ((0 20, 60 20, 60 80, 0 80, 0 20))",
			"POLYGON ((0 25, 60 25, 60 85, 0 85, 0 25))",
			"POLYGON ((-85 70, 85 70, 85 85, -85 85, -85 70))",
			"POLYGON ((0 75, 170 75, 170 88, 0 88, 0 75))",
			"POLYGON ((-60 -89.5, 60 -89.5, 60 -80, -60 -80, -60 -89.5))",
			"POLYGON ((10 88, 100 88, 100 89.9, 10 89.9, 10 88))" };
		org.locationtech.jts.io.WKTReader r = new org.locationtech.jts.io.WKTReader(GF);
		for (String wkt : polys) {
			double pg = jdbc.queryForObject("SELECT ST_Area(ST_GeomFromText(?, 4326)::geography)", Double.class, wkt);
			double ours;
			try {
				ours = GisGeodesic.area(r.read(wkt));
			} catch (org.locationtech.jts.io.ParseException e) {
				throw new IllegalStateException(e);
			}
			System.out.printf("PGAREA|%s|%.0f|%.0f|%+.2e%n", wkt, ours, pg, ours / pg - 1);
			assertThat(ours / pg - 1).isBetween(-1e-6, 1e-6);
		}
	}

	@Test
	@DisplayName("纬度 88–90° 的线和面缓冲：外环顶点到原要素的 ST_Distance(geography) 与设定米数相差 < 0.1%")
	void polarBufferParity() {
		WKTWriter w = new WKTWriter();
		Geometry[] gs = {
			GF.createLineString(new Coordinate[] { new Coordinate(-60, -88), new Coordinate(60, -88.5) }),
			GF.createLineString(new Coordinate[] { new Coordinate(10, 89.5), new Coordinate(100, 89.6) }),
			GF.createPolygon(new Coordinate[] { new Coordinate(0, 88), new Coordinate(40, 88), new Coordinate(40, 89),
				new Coordinate(0, 89), new Coordinate(0, 88) }) };
		for (Geometry g : gs) {
			for (double meters : new double[] { 500d, 5_000d }) {
				Geometry ours = GisGeodesic.buffer(g, meters);
				String target = g.getDimension() == 2 ? "ST_Boundary(ST_GeomFromText(?, 4326))" : "ST_GeomFromText(?, 4326)";
				Double[] range = jdbc.queryForObject(
					"SELECT min(d), max(d) FROM (SELECT ST_Distance((dp).geom::geography, " + target + "::geography) AS d"
						+ " FROM ST_DumpPoints(ST_ExteriorRing(ST_GeometryN(ST_GeomFromText(?, 4326), 1))) dp) t",
					(rs, n) -> new Double[] { rs.getDouble(1), rs.getDouble(2) }, w.write(g), w.write(ours));
				double lo = range[0] / meters - 1, hi = range[1] / meters - 1;
				System.out.printf("PGPOLAR|%s|%.0f|%+.4f%%|%+.4f%%%n", w.write(g), meters, lo * 100, hi * 100);
				assertThat(lo).isGreaterThan(-0.001);
				assertThat(hi).isLessThan(0.001);
			}
		}
	}
}
