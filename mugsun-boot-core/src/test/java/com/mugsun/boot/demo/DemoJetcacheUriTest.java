package com.mugsun.boot.demo;

import org.junit.jupiter.api.Test;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.PropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.ClassPathResource;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 按 prod,demo 叠加后 jetcache 远端 URI 必须沿用 prod 的口令与主机变量，只把 db 换成演示专用库（安全复审 N1）。
 */
class DemoJetcacheUriTest {

	private static StandardEnvironment prodDemo(Map<String, Object> deploy) throws Exception {
		StandardEnvironment env = new StandardEnvironment();
		var sources = env.getPropertySources();
		sources.addFirst(new MapPropertySource("deploy", deploy));
		YamlPropertySourceLoader loader = new YamlPropertySourceLoader();
		for (String file : List.of("application.yml", "application-prod.yml", "application-demo.yml")) {
			for (PropertySource<?> ps : loader.load(file, new ClassPathResource(file))) {
				sources.addAfter("deploy", ps);
			}
		}
		return env;
	}

	@Test
	void demoTrustsLoopbackAndPrivateProxiesByDefault() throws Exception {
		String raw = prodDemo(Map.of()).getProperty("mugsun.openapi.trusted-proxies");
		List<com.mugsun.boot.common.web.IpCidr> cidrs = com.mugsun.boot.common.web.IpCidr.parseList(raw);
		assertThat(cidrs).hasSize(6);
		for (String ip : List.of("127.0.0.1", "::1", "10.1.2.3", "172.30.88.10", "192.168.1.1", "fd00::1")) {
			assertThat(com.mugsun.boot.common.web.IpCidr.anyContains(cidrs, ip)).as(ip).isTrue();
		}
		assertThat(com.mugsun.boot.common.web.IpCidr.anyContains(cidrs, "203.0.113.9")).isFalse();
		// 部署方显式配置时以部署方为准
		assertThat(prodDemo(Map.of("MUGSUN_OPENAPI_TRUSTED_PROXIES", "172.30.88.10/32"))
			.getProperty("mugsun.openapi.trusted-proxies")).isEqualTo("172.30.88.10/32");
	}

	@Test
	void prodDemoUriKeepsPasswordAndUsesDemoDb() throws Exception {
		StandardEnvironment env = new StandardEnvironment();
		var sources = env.getPropertySources();
		sources.addFirst(new MapPropertySource("deploy", Map.of(
			"REDIS_PASSWORD", "s3cret-pw", "REDIS_HOST", "redis", "REDIS_PORT", "6379", "REDIS_DB", "0")));
		YamlPropertySourceLoader loader = new YamlPropertySourceLoader();
		// 后加载的 profile 优先：demo > prod > 默认
		for (String file : List.of("application.yml", "application-prod.yml", "application-demo.yml")) {
			for (PropertySource<?> ps : loader.load(file, new ClassPathResource(file))) {
				sources.addAfter("deploy", ps);
			}
		}
		String uri = env.getProperty("jetcache.remote.default.uri");
		assertThat(uri).isEqualTo("redis://:s3cret-pw@redis:6379/4");
	}
}
