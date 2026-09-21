package com.mugsun.boot;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.ResponseEntity;

import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 缓存管理回归：分组/键/值/清除；权限门控；Redis 反证。
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class CacheApiTest extends AbstractIntegrationTest {

	private static final String KEY = "mugsun:it-cache:probe";
	private static final String GROUP = "mugsun:it-cache";

	@Autowired
	private StringRedisTemplate redisTemplate;

	private String adminToken;

	@BeforeAll
	void setup() {
		adminToken = loginAdmin();
	}

	@Test
	void unauthenticatedIsUnauthorized() {
		ResponseEntity<String> resp = get("/system/cache/groups", null);
		assertThat(resp.getStatusCode().value()).isEqualTo(401);
	}

	@Test
	void groupsKeysValueAndRemove() {
		redisTemplate.opsForValue().set(KEY, "hello-it", 120, TimeUnit.SECONDS);

		JsonNode groups = readBody(get("/system/cache/groups", adminToken));
		assertThat(groups.path("code").asInt()).isEqualTo(200);
		boolean foundGroup = false;
		for (JsonNode g : groups.path("data")) {
			if (GROUP.equals(g.path("name").asText())) {
				foundGroup = true;
				assertThat(g.path("count").asInt()).isGreaterThanOrEqualTo(1);
			}
		}
		assertThat(foundGroup).as("分组应含 " + GROUP).isTrue();

		JsonNode keys = readBody(get("/system/cache/keys?group=" + GROUP, adminToken));
		assertThat(keys.path("code").asInt()).isEqualTo(200);
		boolean foundKey = false;
		for (JsonNode k : keys.path("data")) {
			if (KEY.equals(k.asText())) {
				foundKey = true;
			}
		}
		assertThat(foundKey).isTrue();

		JsonNode value = readBody(get("/system/cache/value?key=" + KEY, adminToken));
		assertThat(value.path("code").asInt()).isEqualTo(200);
		assertThat(value.path("data").path("value").asText()).isEqualTo("hello-it");
		assertThat(value.path("data").path("ttl").asLong()).isPositive();

		JsonNode remove = readBody(post("/system/cache/remove", List.of(KEY), adminToken));
		assertThat(remove.path("code").asInt()).isEqualTo(200);
		assertThat(redisTemplate.hasKey(KEY)).isFalse();
	}
}
