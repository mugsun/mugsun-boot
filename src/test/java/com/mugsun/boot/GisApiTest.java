package com.mugsun.boot;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * GIS 模块：默认开启、无 Key 时 status 可用、场景 CRUD、关闭参数后接口拒绝。
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class GisApiTest extends AbstractIntegrationTest {

	private String adminToken;

	@Autowired
	private com.mugsun.boot.system.service.ParamService paramService;

	@BeforeAll
	void setup() {
		adminToken = loginAdmin();
	}

	@Test
	void statusEnabledByDefaultAndSceneCrud() {
		JsonNode status = readBody(get("/system/gis/status", adminToken));
		assertThat(status.path("code").asInt()).isEqualTo(200);
		assertThat(status.path("data").path("enabled").asBoolean()).as("默认开启").isTrue();
		assertThat(status.path("data").path("providers").isArray()).isTrue();
		assertThat(status.path("data").path("providers").size()).isEqualTo(4);

		Map<String, Object> scene = new HashMap<>();
		scene.put("name", "IT场景-" + System.currentTimeMillis());
		JsonNode created = readBody(post("/system/gis/scene/submit", scene, adminToken));
		assertThat(created.path("code").asInt()).isEqualTo(200);
		long id = created.path("data").path("id").asLong();
		assertThat(id).isPositive();
		assertThat(created.path("data").path("sceneJson").asText()).contains("viewMode");

		JsonNode detail = readBody(get("/system/gis/scene/detail/" + id, adminToken));
		assertThat(detail.path("code").asInt()).isEqualTo(200);
		assertThat(detail.path("data").path("name").asText()).isEqualTo(scene.get("name"));

		JsonNode page = readBody(get("/system/gis/scene/page?pageNum=1&pageSize=10", adminToken));
		assertThat(page.path("code").asInt()).isEqualTo(200);
		assertThat(page.path("data").path("totalRow").asLong()).isPositive();

		assertThat(readBody(post("/system/gis/scene/remove", List.of(id), adminToken)).path("code").asInt())
			.isEqualTo(200);

		JsonNode gone = readBody(get("/system/gis/scene/detail/" + id, adminToken));
		assertThat(gone.path("code").asInt()).isNotEqualTo(200);
	}

	@Test
	void disableModuleRejectsWriteThenRestore() {
		paramService.setValue("gis.module.enabled", "false");
		try {
			JsonNode status = readBody(get("/system/gis/status", adminToken));
			assertThat(status.path("data").path("enabled").asBoolean()).isFalse();
			Map<String, Object> scene = new HashMap<>();
			scene.put("name", "should-reject");
			JsonNode write = readBody(post("/system/gis/scene/submit", scene, adminToken));
			assertThat(write.path("code").asInt()).isEqualTo(400);
			assertThat(write.path("msg").asText()).contains("未启用");
		} finally {
			paramService.setValue("gis.module.enabled", "true");
		}
		JsonNode on = readBody(get("/system/gis/status", adminToken));
		assertThat(on.path("data").path("enabled").asBoolean()).isTrue();
	}

	@Test
	void infoCarriesGisEnabledAndUnauthorizedStatus() {
		JsonNode info = readBody(get("/auth/info", adminToken));
		assertThat(info.path("data").path("gisEnabled").asBoolean()).isTrue();
		ResponseEntity<String> anon = get("/system/gis/status", null);
		assertThat(anon.getStatusCode().value()).isEqualTo(401);
		ResponseEntity<String> anonSearch = get("/system/gis/search?q=beijing", null);
		assertThat(anonSearch.getStatusCode().value()).isEqualTo(401);
		JsonNode blank = readBody(get("/system/gis/search?q=a", adminToken));
		assertThat(blank.path("code").asInt()).isEqualTo(400);
		assertThat(blank.path("msg").asText()).contains("2");
		JsonNode missingProvider = readBody(get("/system/gis/search?q=beijing", adminToken));
		assertThat(missingProvider.path("code").asInt()).isEqualTo(400);
		assertThat(missingProvider.path("msg").asText()).contains("指定");
		JsonNode unknownProvider = readBody(get("/system/gis/search?q=beijing&provider=not-a-vendor", adminToken));
		assertThat(unknownProvider.path("code").asInt()).isEqualTo(400);
		assertThat(unknownProvider.path("msg").asText()).contains("未知");
	}

	@Test
	void providerSubmitMasksKey() {
		Map<String, Object> body = new HashMap<>();
		body.put("provider", "tianditu");
		body.put("enabled", 1);
		body.put("apiKey", "demo-tk-" + System.currentTimeMillis());
		assertThat(readBody(post("/system/gis/provider/submit", body, adminToken)).path("code").asInt())
			.isEqualTo(200);
		JsonNode list = readBody(get("/system/gis/provider/list", adminToken));
		assertThat(list.path("code").asInt()).isEqualTo(200);
		boolean found = false;
		for (JsonNode row : list.path("data")) {
			if ("tianditu".equals(row.path("provider").asText())) {
				found = true;
				assertThat(row.path("apiKey").isNull() || row.path("apiKey").asText().isBlank())
					.as("密钥不得回传").isTrue();
			}
		}
		assertThat(found).isTrue();
	}

	@Test
	void layerIngestsBusinessCoordsAndPersists() {
		Map<String, Object> row = new HashMap<>();
		row.put("longitude", 116.397428);
		row.put("latitude", 39.90923);
		row.put("title", "演示点");
		row.put("bizId", "A-1");
		JsonNode ingested = readBody(post("/system/gis/layer/ingest", List.of(row), adminToken));
		assertThat(ingested.path("code").asInt()).isEqualTo(200);
		assertThat(ingested.path("data").path("count").asInt()).isEqualTo(1);
		assertThat(ingested.path("data").path("crs").asText()).isEqualTo("EPSG:4326");
		assertThat(ingested.path("data").path("features").get(0).path("properties").path("bizId").asText())
			.isEqualTo("A-1");

		JsonNode empty = readBody(post("/system/gis/layer/ingest", Map.of("foo", "bar"), adminToken));
		assertThat(empty.path("code").asInt()).isEqualTo(400);

		Map<String, Object> submit = new HashMap<>();
		submit.put("name", "IT图层-" + System.currentTimeMillis());
		submit.put("payload", List.of(row));
		JsonNode created = readBody(post("/system/gis/layer/submit", submit, adminToken));
		assertThat(created.path("code").asInt()).isEqualTo(200);
		long id = created.path("data").path("id").asLong();
		assertThat(created.path("data").path("featureCount").asInt()).isEqualTo(1);
		assertThat(created.path("data").path("crs").asText()).isEqualTo("EPSG:4326");

		JsonNode detail = readBody(get("/system/gis/layer/detail/" + id, adminToken));
		assertThat(detail.path("code").asInt()).isEqualTo(200);
		assertThat(detail.path("data").path("dataJson").asText()).contains("bizId");

		JsonNode page = readBody(get("/system/gis/layer/page?pageNum=1&pageSize=10", adminToken));
		assertThat(page.path("code").asInt()).isEqualTo(200);
		assertThat(page.path("data").path("records").get(0).path("dataJson").isNull()
			|| page.path("data").path("records").get(0).path("dataJson").asText("").isBlank()).isTrue();

		assertThat(readBody(post("/system/gis/layer/remove", List.of(id), adminToken)).path("code").asInt())
			.isEqualTo(200);
	}

	@Test
	void ingestWktAndCsvThenBufferMeters() {
		JsonNode wkt = readBody(post("/system/gis/layer/ingest",
			Map.of("payload", "POINT (116.397428 39.90923)"), adminToken));
		assertThat(wkt.path("code").asInt()).isEqualTo(200);
		assertThat(wkt.path("data").path("count").asInt()).isEqualTo(1);
		assertThat(wkt.path("data").path("features").get(0).path("geometry").path("type").asText())
			.isEqualTo("Point");

		String csv = "lon,lat,name,bizId\n116.397428,39.90923,天安门,T-1\n";
		JsonNode csvNode = readBody(post("/system/gis/layer/ingest", Map.of("payload", csv), adminToken));
		assertThat(csvNode.path("code").asInt()).isEqualTo(200);
		assertThat(csvNode.path("data").path("features").get(0).path("properties").path("bizId").asText())
			.isEqualTo("T-1");

		Map<String, Object> analyze = new HashMap<>();
		analyze.put("op", "buffer");
		analyze.put("distance", 500);
		analyze.put("payload", "POINT (116.397428 39.90923)");
		JsonNode buf = readBody(post("/system/gis/geo/analyze", analyze, adminToken));
		assertThat(buf.path("code").asInt()).isEqualTo(200);
		assertThat(buf.path("data").path("op").asText()).isEqualTo("buffer");
		assertThat(buf.path("data").path("collection").path("features").get(0).path("geometry").path("type").asText())
			.isEqualTo("Polygon");
		assertThat(buf.path("data").path("metrics").path("areaSqMeters").asDouble())
			.isGreaterThan(700_000d);

		JsonNode badOp = readBody(post("/system/gis/geo/analyze", Map.of("op", "warp", "payload", "POINT (1 1)"), adminToken));
		assertThat(badOp.path("code").asInt()).isEqualTo(400);

		ResponseEntity<String> anon = post("/system/gis/geo/analyze", Map.of("op", "buffer"), null);
		assertThat(anon.getStatusCode().value()).isEqualTo(401);
	}

	@Test
	void demoCatalogReadyToPlay() {
		JsonNode list = readBody(get("/system/gis/demo/list", adminToken));
		assertThat(list.path("code").asInt()).isEqualTo(200);
		assertThat(list.path("data").size()).isEqualTo(10);
		JsonNode poi = readBody(get("/system/gis/demo/poi", adminToken));
		assertThat(poi.path("code").asInt()).isEqualTo(200);
		assertThat(poi.path("data").path("count").asInt()).isEqualTo(8);
		assertThat(poi.path("data").path("features").get(0).path("properties").path("name").asText())
			.isEqualTo("天安门");
		JsonNode play = readBody(get("/system/gis/demo/playback", adminToken));
		assertThat(play.path("code").asInt()).isEqualTo(200);
		assertThat(play.path("data").path("features").get(0).path("properties").path("times").isArray()).isTrue();
		assertThat(play.path("data").path("features").get(0).path("geometry").path("type").asText())
			.isEqualTo("LineString");
		JsonNode tiles3d = readBody(get("/system/gis/demo/tiles3d", adminToken));
		assertThat(tiles3d.path("data").path("url").asText()).isEqualTo("hosted:demo-city");
		assertThat(tiles3d.path("data").path("count").asInt()).isEqualTo(16);
		JsonNode manifest = readBody(get("/system/gis/tileset/demo-city/tileset.json", adminToken));
		assertThat(manifest.path("root").path("children").size()).isEqualTo(4);
		assertThat(get("/system/gis/tileset/demo-city/tileset.json", null).getStatusCode().value()).isEqualTo(401);
		JsonNode geo = readBody(get("/system/gis/demo/geocode", adminToken));
		assertThat(geo.path("data").path("count").asInt()).isEqualTo(0);
		JsonNode missing = readBody(get("/system/gis/demo/warp", adminToken));
		assertThat(missing.path("code").asInt()).isEqualTo(400);
		assertThat(get("/system/gis/demo/list", null).getStatusCode().value()).isEqualTo(401);
	}
}
