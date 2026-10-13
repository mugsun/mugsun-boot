package com.mugsun.boot.gis;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mugsun.boot.gis.engine.GisMapEngine;
import com.mugsun.boot.gis.engine.GisMapEngines;
import com.mugsun.boot.gis.engine.GisUpstreamHttp;
import com.mugsun.boot.gis.entity.GisMapProvider;
import com.mugsun.boot.gis.mapper.GisMapProviderMapper;
import com.mugsun.boot.gis.tile.GisTileFailure;
import com.mugsun.boot.gis.tile.GisTileFailureLog;
import com.mugsun.boot.gis.xyz.GisXyzProperties;
import com.mugsun.boot.gis.xyz.GisXyzTileService;
import com.mybatisflex.core.query.QueryWrapper;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;

import java.net.InetAddress;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 端到端走 GisTileService：失败按原因回码 + 原因头；WARN 里只有 host，没有 URL 和密钥。
 */
class GisTileServiceFailureTest {

	private static final String SECRET = "SECRET-tk-123456";

	private final List<String> logs = new ArrayList<>();

	private GisTileService service(GisMapProvider row, GisXyzGuard_Resolver resolver, GisMapEngine engine) {
		GisMapProviderMapper mapper = mock(GisMapProviderMapper.class);
		when(mapper.selectOneByQuery(any(QueryWrapper.class))).thenReturn(row);
		GisXyzTileService xyz = new GisXyzTileService(new GisXyzProperties(), new GisUpstreamHttp(new ObjectMapper()),
			new ObjectMapper(), resolver::resolve);
		GisTileService svc = new GisTileService(mapper, new GisMapEngines(engine == null ? List.of() : List.of(engine)),
			new GisUpstreamHttp(new ObjectMapper()), xyz);
		svc.setFailureLog(new GisTileFailureLog(60_000, () -> 0L,
			(p, h, r, s, z, sup) -> logs.add(p + " " + h + " " + r.code() + " " + s + " " + z)));
		return svc;
	}

	interface GisXyzGuard_Resolver {
		InetAddress[] resolve(String host) throws java.net.UnknownHostException;
	}

	@Test
	void fakeIpBenchmarkAddressGives403WithReasonAndOneWarn() {
		GisTileService svc = service(null, h -> new InetAddress[] { InetAddress.getByName("198.18.4.21") }, null);
		ResponseEntity<byte[]> r = svc.fetch("xyz", "base", 3, 1, 1);
		assertThat(r.getStatusCode().value()).isEqualTo(403);
		assertThat(r.getHeaders().getFirst(GisTileFailure.HEADER)).isEqualTo("blocked-address");
		svc.fetch("xyz", "base", 3, 2, 1);
		assertThat(logs).containsExactly("xyz tile.openstreetmap.org blocked-address 0 3");
	}

	@Test
	void keyedTemplateNeverLeaksKeyOrUrlIntoLog() {
		GisMapProvider row = new GisMapProvider();
		row.setProvider("xyz");
		row.setEnabled(GisConstants.STATUS_ENABLE);
		row.setApiKey(SECRET);
		row.setExtraJson("{\"url\":\"https://{s}.tile.openstreetmap.org/{z}/{x}/{y}.png?apikey={key}\","
			+ "\"subdomains\":[\"a\",\"b\"],\"attribution\":\"x\",\"maxZoom\":19}");
		GisTileService svc = service(row, h -> new InetAddress[] { InetAddress.getByName("10.0.0.8") }, null);
		ResponseEntity<byte[]> r = svc.fetch("xyz", "base", 4, 3, 2);
		assertThat(r.getStatusCode().value()).isEqualTo(403);
		assertThat(logs).hasSize(1);
		assertThat(logs.get(0)).contains("a.tile.openstreetmap.org").doesNotContain(SECRET).doesNotContain("apikey")
			.doesNotContain("/4/");
	}

	@Test
	void vendorHostOutsideWhitelistGives403AndNoKeyInLog() {
		GisMapProvider row = new GisMapProvider();
		row.setProvider("tianditu");
		row.setEnabled(GisConstants.STATUS_ENABLE);
		row.setApiKey(SECRET);
		GisMapEngine engine = new GisMapEngine() {
			public String code() {
				return "tianditu";
			}

			public String tileUri(String layer, int z, int x, int y, String key) {
				return "https://evil.example.com/t?tk=" + key;
			}

			public List<Map<String, Object>> search(String q, Double a, Double b, String key) {
				return List.of();
			}

			public Map<String, Object> reverse(double a, double b, String key) {
				return Map.of();
			}

			public boolean allowedTileHost(String host) {
				return "t0.tianditu.gov.cn".equals(host);
			}
		};
		GisTileService svc = service(row, h -> new InetAddress[0], engine);
		ResponseEntity<byte[]> r = svc.fetch("tianditu", "vec", 3, 1, 1);
		assertThat(r.getStatusCode().value()).isEqualTo(403);
		assertThat(r.getHeaders().getFirst(GisTileFailure.HEADER)).isEqualTo("host-not-allowed");
		assertThat(logs).containsExactly("tianditu evil.example.com host-not-allowed 0 3");
	}

	@Test
	void notConfiguredAndOutOfRangeAre404WithoutWarn() {
		GisTileService svc = service(null, h -> new InetAddress[] { InetAddress.getByName("151.101.2.132") }, null);
		ResponseEntity<byte[]> r = svc.fetch("tianditu", "vec", 3, 1, 1);
		assertThat(r.getStatusCode().value()).isEqualTo(404);
		assertThat(r.getHeaders().getFirst(GisTileFailure.HEADER)).isEqualTo("not-configured");
		ResponseEntity<byte[]> oor = svc.fetch("xyz", "base", 2, 9, 9);
		assertThat(oor.getStatusCode().value()).isEqualTo(404);
		assertThat(oor.getHeaders().getFirst(GisTileFailure.HEADER)).isEqualTo("out-of-range");
		assertThat(logs).isEmpty();
	}
}
