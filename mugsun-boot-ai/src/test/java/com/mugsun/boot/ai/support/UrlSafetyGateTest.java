package com.mugsun.boot.ai.support;

import com.mugsun.core.tool.exception.ServiceException;
import org.junit.jupiter.api.Test;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** 不发真实网络请求：IP 字面量或注入解析器。 */
class UrlSafetyGateTest {

	private static final Map<String, String> DNS = Map.of(
		"public.example.com", "93.184.216.34",
		"evil.example.com", "10.0.0.5",
		"ollama.corp.example", "192.168.1.20",
		"gpu.lan.example", "172.16.3.4");

	private static final UrlSafetyGate.HostResolver FAKE = host -> {
		String ip = DNS.get(host);
		if (ip == null) {
			if (host.matches("[0-9.]+") || host.contains(":")) {
				return new InetAddress[]{InetAddress.getByName(host)}; // 字面量，不走 DNS
			}
			throw new UnknownHostException(host);
		}
		return new InetAddress[]{InetAddress.getByName(ip)};
	};

	private static void rejected(String url, List<String> allow) {
		assertThrows(ServiceException.class, () -> UrlSafetyGate.guard(url, allow, FAKE), url);
	}

	@Test
	void rejectsLoopbackPrivateAndMetadata() {
		List<String> none = List.of();
		rejected("http://127.0.0.1:11434/api", none);
		rejected("http://localhost/x", none);
		rejected("http://169.254.169.254/latest/meta-data/", none);
		rejected("http://10.1.2.3/", none);
		rejected("http://192.168.0.1/", none);
		rejected("http://172.16.0.1/", none);
		rejected("http://0.0.0.0/", none);
		rejected("http://[::1]/", none);
		rejected("http://[fd00::1]/", none);
		rejected("http://100.64.0.1/", none);
		rejected("http://metadata.google.internal/", none);
		rejected("http://evil.example.com/hook", none); // DNS 指向内网
		rejected("ftp://public.example.com/", none);
		rejected("file:///etc/passwd", none);
		rejected("http://unknown.invalid/", none);
	}

	@Test
	void allowsPublic() {
		assertEquals("https://public.example.com/hook",
			UrlSafetyGate.guard("https://public.example.com/hook", List.of(), FAKE));
		assertEquals("http://93.184.216.34/x", UrlSafetyGate.guard("http://93.184.216.34/x", List.of(), FAKE));
	}

	@Test
	void allowlistExactHostPermitsPrivate() {
		List<String> allow = List.of("localhost", "127.0.0.1", "ollama.corp.example");
		assertNotNull(UrlSafetyGate.guard("http://localhost:11434/api", allow, FAKE));
		assertNotNull(UrlSafetyGate.guard("http://127.0.0.1:11434/api", allow, FAKE));
		assertNotNull(UrlSafetyGate.guard("http://OLLAMA.corp.example/v1", allow, FAKE));
		rejected("http://169.254.169.254/", allow);
		rejected("http://10.1.2.3/", allow);
	}

	@Test
	void allowlistWildcard() {
		List<String> allow = List.of("*.lan.example");
		assertNotNull(UrlSafetyGate.guard("http://gpu.lan.example/v1", allow, FAKE));
		assertTrue(UrlSafetyGate.isAllowed("a.b.lan.example", allow));
		assertFalse(UrlSafetyGate.isAllowed("lan.example", allow));
		assertFalse(UrlSafetyGate.isAllowed("evillan.example", allow));
		assertFalse(UrlSafetyGate.isAllowed("gpu.lan.example.attacker.com", allow));
		rejected("http://ollama.corp.example/", allow);
	}

	@Test
	void propertiesNullSafe() {
		AiOutboundProperties p = new AiOutboundProperties();
		p.setAllowHosts(null);
		assertTrue(p.getAllowHosts().isEmpty());
		assertThrows(ServiceException.class, () -> p.guard("http://127.0.0.1/"));
	}

	@Test
	void rejectsIpv6EmbeddedAndReservedRanges() throws Exception {
		List<String> none = List.of();
		rejected("http://[::ffff:127.0.0.1]/", none);
		rejected("http://[::ffff:169.254.169.254]/", none);
		rejected("http://[::ffff:10.0.0.1]/", none);
		rejected("http://[::]/", none);
		rejected("http://[fe80::1]/", none);
		rejected("http://[fec0::1]/", none);
		rejected("http://[fd00:ec2::254]/", none);
		rejected("http://[64:ff9b::a9fe:a9fe]/", none); // NAT64 → 169.254.169.254
		rejected("http://[2002:7f00:1::]/", none); // 6to4 → 127.0.0.1
		rejected("http://[ff02::1]/", none);
		rejected("http://100.100.100.200/", none); // 阿里云元数据
		rejected("http://198.18.0.1/", none);
		rejected("http://240.0.0.1/", none);
		rejected("http://255.255.255.255/", none);
		rejected("http://192.0.0.170/", none);
		rejected("http://x.localhost/", none);
		assertFalse(UrlSafetyGate.isPrivate(InetAddress.getByName("2606:4700:4700::1111")));
		assertFalse(UrlSafetyGate.isPrivate(InetAddress.getByName("[64:ff9b::808:808]")));
		assertFalse(UrlSafetyGate.isPrivate(InetAddress.getByName("8.8.8.8")));
	}

	@Test
	void resolveSafeReturnsValidatedAddresses() throws Exception {
		InetAddress[] out = UrlSafetyGate.resolveSafe("public.example.com", List.of(), FAKE);
		assertEquals(InetAddress.getByName("93.184.216.34"), out[0]);
		assertThrows(ServiceException.class, () -> UrlSafetyGate.resolveSafe("evil.example.com", List.of(), FAKE));
		assertThrows(ServiceException.class, () -> UrlSafetyGate.resolveSafe("metadata.google.internal", List.of(), FAKE));
		InetAddress[] mixed = {InetAddress.getByName("93.184.216.34"), InetAddress.getByName("10.0.0.1")};
		assertThrows(ServiceException.class, () -> UrlSafetyGate.resolveSafe("mixed", List.of(), h -> mixed));
	}
}
