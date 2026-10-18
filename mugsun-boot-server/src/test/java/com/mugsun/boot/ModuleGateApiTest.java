package com.mugsun.boot;

import com.fasterxml.jackson.databind.JsonNode;
import com.mugsun.boot.common.module.ModuleGateConfig;
import com.mugsun.boot.system.service.ParamService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.http.server.PathContainer;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;
import org.springframework.web.util.pattern.PathPattern;
import org.springframework.web.util.pattern.PathPatternParser;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 可选模块请求闸门（{@link ModuleGateConfig}）集成测试，默认 full 装配上下文。
 * <ul>
 *   <li>覆盖面：ai/gis/track 包里每个 Controller 的映射都落在对应闸门前缀内，新增模块根路径漏配会在这里失败；</li>
 *   <li>只关系统参数 {@code ai.module.enabled}（yml 仍开、Controller 仍注册）时，AI 接口与「未注册」一样返回 404。</li>
 * </ul>
 * GIS 的同类用例在 {@link GisApiTest#disableModuleByParamReturns404ThenRestore()}。
 */
class ModuleGateApiTest extends AbstractIntegrationTest {

	@Autowired
	private ApplicationContext context;

	@Autowired
	private ParamService paramService;

	private static final PathPatternParser PARSER = PathPatternParser.defaultInstance;

	@Test
	@DisplayName("ai/gis/track 包内每个 Controller 映射都被对应模块闸门覆盖")
	void everyModuleHandlerIsGated() {
		Map<String, String[]> gates = Map.of(
			"com.mugsun.boot.ai", ModuleGateConfig.AI_PATHS,
			"com.mugsun.boot.gis", ModuleGateConfig.GIS_PATHS,
			"com.mugsun.boot.track", ModuleGateConfig.TRACK_PATHS);
		List<String> uncovered = new ArrayList<>();
		int checked = 0;
		for (RequestMappingHandlerMapping mapping : context.getBeansOfType(RequestMappingHandlerMapping.class).values()) {
			for (Map.Entry<RequestMappingInfo, HandlerMethod> e : mapping.getHandlerMethods().entrySet()) {
				String pkg = e.getValue().getBeanType().getPackageName();
				for (Map.Entry<String, String[]> gate : gates.entrySet()) {
					if (!pkg.equals(gate.getKey()) && !pkg.startsWith(gate.getKey() + ".")) {
						continue;
					}
					List<PathPattern> gatePatterns = Arrays.stream(gate.getValue()).map(PARSER::parse).toList();
					for (String p : e.getKey().getPatternValues()) {
						checked++;
						PathContainer path = PathContainer.parsePath(p);
						if (gatePatterns.stream().noneMatch(gp -> gp.matches(path))) {
							uncovered.add(e.getValue().getBeanType().getSimpleName() + " " + p);
						}
					}
				}
			}
		}
		assertThat(checked).as("full 装配下应扫到模块接口").isPositive();
		assertThat(uncovered).as("这些模块接口不在 ModuleGateConfig 前缀内，模块关闭时不会 404").isEmpty();
	}

	@Test
	@DisplayName("只关系统参数 ai.module.enabled：AI 管理端与 /v1 开放接口一律 404，改回后恢复 200")
	void aiParamOnlyShutdownReturns404() {
		String token = loginAdmin();
		String original = paramService.getValue("ai.module.enabled");
		paramService.setValue("ai.module.enabled", "false");
		try {
			assertThat(get("/system/ai/model/page", token).getStatusCode().value()).isEqualTo(404);
			assertThat(post("/system/ai/model/submit", Map.of(), token).getStatusCode().value()).isEqualTo(404);
			assertThat(get("/v1/models", null).getStatusCode().value()).isEqualTo(404);
			assertThat(get("/v1/models", token).getStatusCode().value()).isEqualTo(404);
			assertThat(get("/system/ai/model/page", null).getStatusCode().value()).as("匿名也是 404").isEqualTo(404);
			assertThat(readBody(get("/auth/info", token)).path("data").path("aiEnabled").asBoolean()).isFalse();
			// 核心接口不受影响
			assertThat(get("/system/user/page?pageNum=1&pageSize=1", token).getStatusCode().value()).isEqualTo(200);
		} finally {
			paramService.setValue("ai.module.enabled", original == null || original.isBlank() ? "true" : original);
		}
		JsonNode back = readBody(get("/system/ai/model/page", token));
		assertThat(back.path("code").asInt()).isEqualTo(200);
	}
}
