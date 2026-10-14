package com.mugsun.boot.gis;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import static org.assertj.core.api.Assertions.assertThat;

/** mugsun-boot#63：PostGIS 路径缓冲前识别走向无法确定的要素（极冠），不再静默返回丢了极冠的结果 */
class GisPolarBufferPostgisTest {

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

	private static boolean ambiguous(String wkt) {
		return Boolean.TRUE.equals(jdbc.queryForObject("SELECT " + GisSpatialQueryService.AMBIGUOUS
			+ " FROM (SELECT ST_GeomFromText(?, 4326) AS geom) t", Boolean.class, wkt));
	}

	@Test
	@DisplayName("极冠判为走向不确定；跨 180° 线、普通面、极点附近的点不算")
	void classify() {
		String cap = "POLYGON((-180 80, -90 80, 0 80, 90 80, 180 80, 180 90, -180 90, -180 80))";
		assertThat(ambiguous(cap)).isTrue();
		assertThat(ambiguous("LINESTRING(179.9 0, -179.9 0)")).isFalse();
		assertThat(ambiguous("POLYGON((116 39, 117 39, 117 40, 116 40, 116 39))")).isFalse();
		assertThat(ambiguous("POINT(0 89.99)")).isFalse();
		// 佐证：geography 缓冲确实丢了极冠（结果最北不到 80°N）
		Double maxLat = jdbc.queryForObject(
			"SELECT ST_YMax(ST_Buffer(ST_GeomFromText(?, 4326)::geography, 1000)::geometry)", Double.class, cap);
		assertThat(maxLat).isLessThan(85d);
	}
}
