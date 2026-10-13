package com.mugsun.boot.gis.xyz;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mugsun.boot.gis.engine.GisUpstreamHttp;
import com.mugsun.core.tool.exception.ServiceException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.net.InetAddress;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 自定义 XYZ 底图 SSRF 防线：白名单、IP 字面量、内网解析、协议 / 端口 / userinfo。
 */
class GisXyzGuardTest {

	private static final List<String> ALLOW = List.of("tile.openstreetmap.org", "basemaps.cartocdn.com");

	@Test
	void presetsPassWhitelist() {
		GisXyzPresets.all().values().forEach(t -> assertThat(GisXyzGuard.checkTemplate(t, ALLOW)).isNotNull());
	}

	@Test
	void subdomainOfAllowedHostPasses() {
		assertThat(GisXyzGuard.checkUri("https://a.basemaps.cartocdn.com/light_all/1/0/0.png", ALLOW).getHost())
			.isEqualTo("a.basemaps.cartocdn.com");
		assertThat(GisXyzGuard.hostAllowed("evilbasemaps.cartocdn.com.attacker.io", ALLOW)).isFalse();
		assertThat(GisXyzGuard.hostAllowed("xbasemaps.cartocdn.com", ALLOW)).isFalse();
	}

	@ParameterizedTest
	@ValueSource(strings = {
		"http://tile.openstreetmap.org/1/0/0.png",             // 非 https
		"https://example.com/1/0/0.png",                        // 不在白名单
		"https://tile.openstreetmap.org.evil.com/1/0/0.png",    // 后缀伪装
		"https://user@tile.openstreetmap.org/1/0/0.png",        // userinfo
		"https://tile.openstreetmap.org:8443/1/0/0.png",        // 非默认端口
		"https://127.0.0.1/1/0/0.png",                          // IP 字面量
		"https://169.254.169.254/latest/meta-data",             // 云元数据
		"https://[::1]/1/0/0.png",                              // IPv6 字面量
		"https://2130706433/1/0/0.png",                         // 十进制 IP
		"https://0x7f.0x0.0x0.0x1/1/0/0.png",                   // 十六进制 IP
		"file:///etc/passwd",
		"gopher://tile.openstreetmap.org/"
	})
	void rejectsUnsafeUris(String raw) {
		assertThatThrownBy(() -> GisXyzGuard.checkUri(raw, ALLOW)).isInstanceOf(ServiceException.class);
	}

	@Test
	void templateRequiresZxyAndHttps() {
		assertThatThrownBy(() -> GisXyzGuard.checkTemplate(
			new GisXyzTemplate("https://tile.openstreetmap.org/{z}/{x}.png", List.of(), "", 19), ALLOW))
			.isInstanceOf(ServiceException.class);
		assertThatThrownBy(() -> GisXyzGuard.checkTemplate(
			new GisXyzTemplate("https://{s}.evil.com/{z}/{x}/{y}.png", List.of("a"), "", 19), ALLOW))
			.isInstanceOf(ServiceException.class);
		// {s} 用来拼出白名单外主机
		assertThatThrownBy(() -> GisXyzGuard.checkTemplate(
			new GisXyzTemplate("https://{s}/{z}/{x}/{y}.png", List.of("localhost"), "", 19), ALLOW))
			.isInstanceOf(ServiceException.class);
		assertThatThrownBy(() -> GisXyzGuard.checkTemplate(
			new GisXyzTemplate("https://{s}.basemaps.cartocdn.com/{z}/{x}/{y}.png", List.of("a.b"), "", 19), ALLOW))
			.isInstanceOf(ServiceException.class);
		assertThatThrownBy(() -> GisXyzGuard.checkTemplate(
			new GisXyzTemplate("https://{s}.basemaps.cartocdn.com/{z}/{x}/{y}.png", List.of(), "", 19), ALLOW))
			.isInstanceOf(ServiceException.class);
	}

	@Test
	void attributionIsStrippedOfTags() {
		GisXyzTemplate t = GisXyzGuard.checkTemplate(new GisXyzTemplate(
			"https://tile.openstreetmap.org/{z}/{x}/{y}.png", List.of(), "<b>© OSM</b>", 19), ALLOW);
		assertThat(t.attribution()).doesNotContain("<").doesNotContain(">");
	}

	@Test
	void emptyWhitelistRejectsEverything() {
		assertThatThrownBy(() -> GisXyzGuard.checkTemplate(GisXyzPresets.get(GisXyzPresets.OSM), List.of()))
			.isInstanceOf(ServiceException.class);
	}

	@ParameterizedTest
	@ValueSource(strings = {
		"127.0.0.1", "10.1.2.3", "172.16.0.1", "172.31.255.255", "192.168.1.1", "169.254.169.254",
		"100.64.0.1", "0.0.0.0", "224.0.0.1", "::1", "fe80::1", "fd00::1", "::ffff:127.0.0.1", "::ffff:10.0.0.1"
	})
	void dnsToPrivateAddressIsRejected(String ip) throws Exception {
		InetAddress addr = InetAddress.getByName(ip);
		assertThat(GisXyzGuard.isBlocked(addr)).isTrue();
		assertThatThrownBy(() -> GisXyzGuard.checkResolved("tile.openstreetmap.org", h -> new InetAddress[] { addr }))
			.isInstanceOf(ServiceException.class);
	}

	@Test
	void mixedDnsAnswerWithOnePrivateIsRejected() throws Exception {
		InetAddress pub = InetAddress.getByName("151.101.2.132");
		InetAddress priv = InetAddress.getByName("10.0.0.5");
		assertThatThrownBy(() -> GisXyzGuard.checkResolved("tile.openstreetmap.org", h -> new InetAddress[] { pub, priv }))
			.isInstanceOf(ServiceException.class);
		GisXyzGuard.checkResolved("tile.openstreetmap.org", h -> new InetAddress[] { pub });
	}

	@Test
	void fetchStopsBeforeNetworkWhenDnsIsPrivate() {
		GisXyzProperties props = new GisXyzProperties();
		GisXyzTileService svc = new GisXyzTileService(props, new GisUpstreamHttp(new ObjectMapper()), new ObjectMapper(),
			h -> new InetAddress[] { InetAddress.getByName("127.0.0.1") });
		assertThatThrownBy(() -> svc.fetch(GisXyzPresets.get(GisXyzPresets.OSM), 1, 0, 0, null))
			.isInstanceOf(ServiceException.class)
			.hasMessage(GisXyzGuard.MSG_PRIVATE);
	}

	@Test
	void fetchRejectsOutOfRangeTile() {
		GisXyzTileService svc = new GisXyzTileService(new GisXyzProperties(), new GisUpstreamHttp(new ObjectMapper()),
			new ObjectMapper(), h -> { throw new AssertionError("不应解析"); });
		assertThatThrownBy(() -> svc.fetch(GisXyzPresets.get(GisXyzPresets.OSM), 1, 2, 0, null))
			.isInstanceOf(ServiceException.class);
	}

	@Test
	void normalizeAcceptsPresetAndRejectsForeignHost() {
		GisXyzTileService svc = new GisXyzTileService(new GisXyzProperties(), new GisUpstreamHttp(new ObjectMapper()),
			new ObjectMapper(), GisXyzGuard.SYSTEM_RESOLVER);
		assertThat(svc.normalize("{\"preset\":\"osm\"}")).contains("tile.openstreetmap.org");
		assertThatThrownBy(() -> svc.normalize("{\"url\":\"https://internal.corp/{z}/{x}/{y}.png\"}"))
			.isInstanceOf(ServiceException.class);
	}

	@Test
	void templateRendersSubdomainAndKey() {
		GisXyzTemplate t = new GisXyzTemplate("https://{s}.basemaps.cartocdn.com/{z}/{x}/{y}.png?k={key}",
			List.of("a", "b"), "", 19);
		assertThat(t.render(3, 1, 0, "a b")).isEqualTo("https://b.basemaps.cartocdn.com/3/1/0.png?k=a+b");
	}

	@Test
	void cacheEvictsByBytesAndTtl() {
		GisXyzTileCache cache = new GisXyzTileCache(10, 1);
		cache.put("a", new byte[6], "image/png", 0);
		cache.put("b", new byte[6], "image/png", 0);
		assertThat(cache.get("a", 0)).isNull();
		assertThat(cache.get("b", 0)).isNotNull();
		assertThat(cache.get("b", 2000)).isNull();
	}
}
