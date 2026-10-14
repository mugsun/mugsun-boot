package com.mugsun.boot.gis;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** mugsun-boot#64：库里存的跨 180° 要素在 PostGIS 路径的 bbox / intersects / contains / radius 预筛 */
class GisStoredAntimeridianPostgisTest {

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
		jdbc.execute("CREATE TABLE f (name text, geom geometry(Geometry, 4326))");
		jdbc.execute("CREATE INDEX ON f USING gist (geom)");
		jdbc.execute("INSERT INTO f VALUES"
			+ " ('am-line', ST_GeomFromText('LINESTRING(179.9 0, -179.9 0)', 4326)),"
			+ " ('am-poly', ST_GeomFromText('POLYGON((179 -1, -179 -1, -179 1, 179 1, 179 -1))', 4326)),"
			+ " ('polar-cap', ST_GeomFromText('POLYGON((-180 80, -90 80, 0 80, 90 80, 180 80, 180 90, -180 90, -180 80))', 4326)),"
			+ " ('origin', ST_GeomFromText('POINT(0.5 0)', 4326)),"
			+ " ('east', ST_GeomFromText('POINT(179.97 0)', 4326))");
	}

	@AfterAll
	static void stopDb() {
		if (postgis != null) {
			postgis.stop();
		}
	}

	private static List<String> hits(String pred, String g, Object... gArgs) {
		List<Object> args = new ArrayList<>();
		for (int i = 0; i < GisSpatialQueryService.WRAP_AWARE_G_COUNT; i++) {
			args.addAll(List.of(gArgs));
		}
		return jdbc.queryForList("SELECT name FROM f WHERE true" + GisSpatialQueryService.wrapAwareWhere(pred, g)
			+ " ORDER BY name", String.class, args.toArray());
	}

	@Test
	@DisplayName("0°～10° 的框不再误命中跨 180° 要素")
	void noFalseHitNearZero() {
		assertThat(hits("ST_Intersects", "ST_MakeEnvelope(?, ?, ?, ?, 4326)", 0d, -0.5d, 10d, 0.5d))
			.containsExactly("origin");
	}

	@Test
	@DisplayName("180° 附近（含西>东拆框）能查到跨 180° 要素")
	void hitsNearAntimeridian() {
		assertThat(hits("ST_Intersects",
			"ST_Collect(ST_MakeEnvelope(?, ?, 180, ?, 4326), ST_MakeEnvelope(-180, ?, ?, ?, 4326))",
			179.95d, -0.5d, 0.5d, -0.5d, -179.95d, 0.5d))
			.containsExactly("am-line", "am-poly", "east");
		assertThat(hits("ST_Intersects", "ST_MakeEnvelope(?, ?, ?, ?, 4326)", -179.99d, -0.5d, -179.95d, 0.5d))
			.containsExactly("am-line", "am-poly");
		assertThat(hits("ST_Contains", "ST_SetSRID(ST_MakePoint(?, ?), 4326)", -179.5d, 0.5d))
			.containsExactly("am-poly");
	}

	@Test
	@DisplayName("极冠这类横跨全经度的面照旧按平面判断")
	void polarCapUnchanged() {
		assertThat(hits("ST_Intersects", "ST_MakeEnvelope(?, ?, ?, ?, 4326)", -10d, 84d, 10d, 86d))
			.containsExactly("polar-cap");
	}
}
