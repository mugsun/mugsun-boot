package com.mugsun.boot.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.mugsun.boot.AbstractIntegrationTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.http.ResponseEntity;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * B1 对照组：默认（mugsun.ai.enabled=true，full 装配）时 AI 接口已映射，且真正调用成功（HTTP 200 + 成功码），而不只是「不是 404」。与其他集成测试共用默认上下文。
 */
class AiEnabledApiTest extends AbstractIntegrationTest {

	@Autowired
	private ApplicationContext context;

	@Test
	@DisplayName("AI 接口已映射，管理员访问 HTTP 200 且 R.code=200")
	void aiEndpointsMappedAndSucceed() {
		RequestMappingHandlerMapping mapping =
			context.getBean("requestMappingHandlerMapping", RequestMappingHandlerMapping.class);
		List<String> patterns = new ArrayList<>();
		mapping.getHandlerMethods().keySet().forEach(info -> patterns.addAll(info.getPatternValues()));
		assertThat(patterns).contains("/system/ai/model/page");
		ResponseEntity<String> resp = get("/system/ai/model/page", loginAdmin());
		assertThat(resp.getStatusCode().value()).as("HTTP 状态：" + resp.getBody()).isEqualTo(200);
		JsonNode body = readBody(resp);
		assertThat(body.path("code").asInt()).as("R.code：" + body.path("msg").asText()).isEqualTo(200);
		assertThat(body.path("success").asBoolean(true)).isTrue();
		assertThat(body.path("data").isObject()).as("分页数据").isTrue();
	}
}
