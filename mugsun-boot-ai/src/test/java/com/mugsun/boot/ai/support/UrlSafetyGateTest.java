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
	void allowlistNeverPermitsCloudMetadata() {
		UrlSafetyGate.HostResolver dns = host -> switch (host) {
			case "meta.corp.example" -> new InetAddress[]{InetAddress.getByName("169.254.169.254")};
			case "ali.corp.example" -> new InetAddress[]{InetAddress.getByName("100.100.100.200")};
			case "v6.corp.example" -> new InetAddress[]{InetAddress.getByName("fd00:ec2::254")};
			case "mixed.corp.example" -> new InetAddress[]{InetAddress.getByName("192.168.1.20"),
				InetAddress.getByName("169.254.169.254")};
			case "ollama.corp.example" -> new InetAddress[]{InetAddress.getByName("192.168.1.20")};
			default -> throw new UnknownHostException(host);
		};
		List<String> allow = List.of("169.254.169.254", "100.100.100.200", "fd00:ec2::254", "*.corp.example");
		for (String url : List.of("http://169.254.169.254/latest/meta-data/", "http://100.100.100.200/latest/meta-data/",
			"http://[fd00:ec2::254]/latest/", "http://[::ffff:169.254.169.254]/", "http://meta.corp.example/",
			"http://ali.corp.example/", "http://v6.corp.example/", "http://mixed.corp.example/")) {
			assertThrows(ServiceException.class, () -> UrlSafetyGate.guard(url, allow, dns), url);
			assertThrows(ServiceException.class, () -> UrlSafetyGate.guard(url, allow, List.of(80, 443), dns), url);
		}
		assertThrows(ServiceException.class, () -> UrlSafetyGate.resolveSafe("meta.corp.example", allow, dns));
		// 白名单内网模型照常放行
		assertNotNull(UrlSafetyGate.guard("http://ollama.corp.example:11434/v1", allow, dns));
		assertEquals(1, UrlSafetyGate.resolveSafe("ollama.corp.example", allow, dns).length);
	}

	@Test
	void allowlistNeverPermitsLinkLocalRangeOrEmbeddedV4Metadata() {
		UrlSafetyGate.HostResolver dns = host -> switch (host) {
			case "x.example.com" -> new InetAddress[]{InetAddress.getByName("169.254.170.2")};
			case "tc.example.com" -> new InetAddress[]{InetAddress.getByName("169.254.0.23")};
			case "dns.example.com" -> new InetAddress[]{InetAddress.getByName("169.254.169.253")};
			case "nat64.example.com" -> new InetAddress[]{InetAddress.getByName("64:ff9b::a9fe:aa02")};
			case "sixto4.example.com" -> new InetAddress[]{InetAddress.getByName("2002:a9fe:aa02::")};
			case "compat.example.com" -> new InetAddress[]{InetAddress.getByName("::a9fe:aa02")};
			case "ok.example.com" -> new InetAddress[]{InetAddress.getByName("192.168.1.20")};
			default -> throw new UnknownHostException(host);
		};
		List<String> allow = List.of("*.example.com", "169.254.170.2", "64:ff9b::a9fe:a9fe", "2002:a9fe:a9fe::",
			"::a9fe:a9fe", "::ffff:169.254.0.23");
		for (String host : List.of("x.example.com", "tc.example.com", "dns.example.com", "nat64.example.com",
			"sixto4.example.com", "compat.example.com")) {
			String url = "http://" + host + "/v2/credentials";
			// 保存（guard）与连接（resolveSafe）都拦
			assertThrows(ServiceException.class, () -> UrlSafetyGate.guard(url, allow, dns), url);
			assertThrows(ServiceException.class, () -> UrlSafetyGate.guard(url, allow, List.of(80, 443), dns), url);
			assertThrows(ServiceException.class, () -> UrlSafetyGate.resolveSafe(host, allow, dns), host);
		}
		for (String url : List.of("http://169.254.170.2/v2/credentials", "http://[64:ff9b::a9fe:a9fe]/",
			"http://[2002:a9fe:a9fe::]/", "http://[::a9fe:a9fe]/", "http://[::ffff:169.254.0.23]/")) {
			assertThrows(ServiceException.class, () -> UrlSafetyGate.guard(url, allow, dns), url);
		}
		// 非白名单路径同样拦
		for (String url : List.of("http://[64:ff9b::a9fe:a9fe]/", "http://[2002:a9fe:a9fe::]/", "http://[::a9fe:a9fe]/",
			"http://169.254.170.2/", "http://[64:ff9b::a64:6464]/")) {
			assertThrows(ServiceException.class, () -> UrlSafetyGate.guard(url, List.of(), dns), url);
		}
		assertNotNull(UrlSafetyGate.guard("http://ok.example.com:11434/v1", allow, dns));
	}

	@Test
	void cloudMetadataDetection() throws Exception {
		for (String ip : List.of("169.254.0.0", "169.254.170.2", "169.254.0.23", "169.254.169.253", "169.254.255.255",
			"100.100.100.200", "fd00:ec2::254", "::ffff:169.254.170.2", "::a9fe:aa02", "64:ff9b::a9fe:a9fe",
			"2002:a9fe:a9fe::", "64:ff9b::6464:64c8", "2002:6464:64c8::")) {
			assertTrue(UrlSafetyGate.isCloudMetadata(InetAddress.getByName(ip)), ip);
		}
		for (String ip : List.of("169.253.255.255", "169.255.0.1", "100.100.100.201", "192.168.1.20", "::1",
			"2001:db8::1", "64:ff9b::808:808", "2002:808:808::")) {
			assertFalse(UrlSafetyGate.isCloudMetadata(InetAddress.getByName(ip)), ip);
		}
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
		rejected("http://192.0.2.1/", none);
		rejected("http://192.88.99.1/", none);
		rejected("http://198.51.100.7/", none);
		rejected("http://203.0.113.9/", none);
		rejected("http://[2001:db8::1]/", none);
		rejected("http://[2001:0:4136:e378:8000:63bf:3fff:fdd2]/", none); // Teredo
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

	@Test
	void portPolicy() {
		List<Integer> ports = List.of(80, 443);
		assertNotNull(UrlSafetyGate.guard("https://public.example.com/v1", List.of(), ports, FAKE));
		assertNotNull(UrlSafetyGate.guard("http://public.example.com:80/v1", List.of(), ports, FAKE));
		assertThrows(ServiceException.class, () -> UrlSafetyGate.guard("http://public.example.com:6379/", List.of(), ports, FAKE));
		assertThrows(ServiceException.class, () -> UrlSafetyGate.guard("https://93.184.216.34:25/", List.of(), ports, FAKE));
		assertNotNull(UrlSafetyGate.guard("http://localhost:11434/v1", List.of("localhost"), ports, FAKE), "白名单主机不限端口");
		assertNotNull(UrlSafetyGate.guard("http://public.example.com:8080/", List.of(), List.of(), FAKE), "空列表不限端口");
	}
}
