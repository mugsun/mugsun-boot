package com.mugsun.boot;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 冒烟：Spring 上下文在容器 PG/Redis 上完整启动（Flyway 迁移 + DataInitializer 播种），健康检查 UP。
 */
class SmokeApiTest extends AbstractIntegrationTest {

	@Test
	void contextStartsAndHealthIsUp() {
		ResponseEntity<String> response = get("/actuator/health", null);
		assertThat(response.getStatusCode().value()).isEqualTo(200);
		JsonNode body = readBody(response);
		assertThat(body.path("status").asText()).isEqualTo("UP");
	}
}
