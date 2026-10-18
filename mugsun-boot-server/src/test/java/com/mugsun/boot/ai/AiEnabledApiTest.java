package com.mugsun.boot.ai;

import com.mugsun.boot.AbstractIntegrationTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * B1 对照组：默认（mugsun.ai.enabled=true，full 装配）时 AI 接口已映射且可访问。与其他集成测试共用默认上下文。
 */
class AiEnabledApiTest extends AbstractIntegrationTest {

	@Autowired
	private ApplicationContext context;

	@Test
	@DisplayName("AI 接口已映射，管理员访问不是 404")
	void aiEndpointsMapped() {
		RequestMappingHandlerMapping mapping =
			context.getBean("requestMappingHandlerMapping", RequestMappingHandlerMapping.class);
		List<String> patterns = new ArrayList<>();
		mapping.getHandlerMethods().keySet().forEach(info -> patterns.addAll(info.getPatternValues()));
		assertThat(patterns).contains("/system/ai/model/page");
		assertThat(get("/system/ai/model/page", loginAdmin()).getStatusCode().value()).isNotEqualTo(404);
	}
}
