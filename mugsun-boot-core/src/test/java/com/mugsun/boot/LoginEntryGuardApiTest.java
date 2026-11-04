package com.mugsun.boot;

import com.mugsun.boot.security.LoginPublicPaths;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;
import org.springframework.web.util.pattern.PathPattern;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * #93 入口守卫：枚举 MVC {@link RequestMappingHandlerMapping} 路由，匿名访问既非 401/403、
 * 又不在 {@link LoginPublicPaths} → 失败。白名单变更须改常量（本测自动跟）。
 * <p>仅扫主映射 {@code requestMappingHandlerMapping}（不含 actuator 的
 * {@code controllerEndpointHandlerMapping}）；actuator 探活/鉴权由 #94 单测与下方抽样覆盖。
 */
class LoginEntryGuardApiTest extends AbstractIntegrationTest {

	private static final Pattern PATH_VAR = Pattern.compile("\\{[^}]+}");

	@Autowired
	@Qualifier("requestMappingHandlerMapping")
	private RequestMappingHandlerMapping handlerMapping;

	@Test
	void anonymousNonPublicRoutesMustBe401Or403() {
		List<String> leaks = new ArrayList<>();
		handlerMapping.getHandlerMethods().forEach((info, method) -> {
			for (String pattern : patternsOf(info)) {
				String probe = concretize(pattern);
				// 模板或具体探针命中白名单则跳过（前缀规则可匹配含 {var} 的模式）
				if (LoginPublicPaths.isPublic(probe) || LoginPublicPaths.isPublic(pattern)) {
					continue;
				}
				Set<RequestMethod> methods = info.getMethodsCondition().getMethods();
				if (methods == null || methods.isEmpty()) {
					methods = EnumSet.of(RequestMethod.GET, RequestMethod.POST, RequestMethod.PUT,
						RequestMethod.DELETE, RequestMethod.PATCH);
				}
				for (RequestMethod rm : methods) {
					if (rm == RequestMethod.OPTIONS || rm == RequestMethod.HEAD || rm == RequestMethod.TRACE) {
						continue;
					}
					HttpMethod http = HttpMethod.valueOf(rm.name());
					ResponseEntity<String> resp;
					try {
						resp = rest.exchange(probe, http, new HttpEntity<>(authHeaders(null)), String.class);
					} catch (Exception e) {
						leaks.add(rm + " " + pattern + " → exchange error: " + e.getMessage());
						continue;
					}
					int code = resp.getStatusCode().value();
					if (code != 401 && code != 403) {
						leaks.add(rm + " " + pattern + " (probe " + probe + ") → HTTP " + code);
					}
				}
			}
		});
		assertThat(leaks)
			.as("匿名可访问且不在 LoginPublicPaths 的路由（须补白名单或补登录）:\n" + String.join("\n", leaks))
			.isEmpty();
	}

	@Test
	void publicWhitelistStaysReachableWithoutForced401() {
		assertThat(get("/ping", null).getStatusCode().value()).isNotIn(401, 403);
		assertThat(get("/actuator/health", null).getStatusCode().value()).isEqualTo(200);
		assertThat(get("/auth/sm2-public-key", null).getStatusCode().value()).isEqualTo(200);
		assertThat(get("/auth/captcha/config", null).getStatusCode().value()).isNotIn(401, 403);
		assertThat(get("/v3/api-docs", null).getStatusCode().value()).isEqualTo(200);
	}

	@Test
	void protectedSampleRequiresLogin() {
		assertThat(get("/auth/info", null).getStatusCode().value()).isEqualTo(401);
		assertThat(get("/actuator/metrics", null).getStatusCode().value()).isEqualTo(401);
		assertThat(get("/system/user/page", null).getStatusCode().value()).isEqualTo(401);
		// 未注册旁路口仍须 404（不因全局登录先回 401）
		assertThat(post("/auth/sms/send?phone=13800138000", java.util.Map.of(), null).getStatusCode().value())
			.isEqualTo(404);
	}

	private static Set<String> patternsOf(RequestMappingInfo info) {
		if (info.getPathPatternsCondition() != null) {
			return info.getPathPatternsCondition().getPatterns().stream()
				.map(PathPattern::getPatternString)
				.collect(Collectors.toSet());
		}
		if (info.getPatternsCondition() != null) {
			return info.getPatternsCondition().getPatterns();
		}
		return Set.of();
	}

	/** 把 /a/{id}/b → /a/1/b；{*path} → x */
	static String concretize(String pattern) {
		Matcher m = PATH_VAR.matcher(pattern);
		StringBuffer sb = new StringBuffer();
		while (m.find()) {
			String var = m.group();
			String repl = var.startsWith("{*") ? "x" : "1";
			m.appendReplacement(sb, Matcher.quoteReplacement(repl));
		}
		m.appendTail(sb);
		return sb.toString();
	}
}
