package com.mugsun.boot.demo;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mugsun.boot.system.controller.CryptoDemoController;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.core.type.filter.AnnotationTypeFilter;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;

import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * 只读演示模式全量端点断言：反射遍历 com.mugsun 下所有 @Controller 的映射（不起 Spring 上下文、不连库），
 * 断言非 GET/HEAD/OPTIONS 的映射只有白名单能放行——新加的写端点默认被拒，漏拦即测试失败。
 * <p>同时生成端点清单 docs/demo/readonly-endpoints.md；清单与代码不一致时测试失败，
 * 用 {@code mvn -pl mugsun-boot-server test -Dtest=DemoReadOnlyEndpointsTest -Ddemo.endpoints.write=true} 重新生成。
 */
class DemoReadOnlyEndpointsTest {

	private static final Set<String> WRITE_METHODS = Set.of("POST", "PUT", "DELETE", "PATCH");
	private static final DemoReadOnlyPolicy POLICY = new DemoReadOnlyPolicy(true);
	private static final Path INVENTORY = Path.of("..", "docs", "demo", "readonly-endpoints.md");

	record Endpoint(String method, String path, String handler) {
	}

	private static List<Endpoint> endpoints;

	@BeforeAll
	static void scan() throws Exception {
		endpoints = new ArrayList<>();
		ClassPathScanningCandidateComponentProvider scanner = new ClassPathScanningCandidateComponentProvider(false);
		scanner.addIncludeFilter(new AnnotationTypeFilter(Controller.class));
		for (BeanDefinition bd : scanner.findCandidateComponents("com.mugsun")) {
			Class<?> type = Class.forName(bd.getBeanClassName(), false, DemoReadOnlyEndpointsTest.class.getClassLoader());
			RequestMapping classMapping = AnnotatedElementUtils.findMergedAnnotation(type, RequestMapping.class);
			String[] prefixes = classMapping == null || classMapping.path().length == 0 ? new String[]{""} : classMapping.path();
			for (Method m : type.getDeclaredMethods()) {
				RequestMapping rm = AnnotatedElementUtils.findMergedAnnotation(m, RequestMapping.class);
				if (rm == null) {
					continue;
				}
				String[] paths = rm.path().length == 0 ? new String[]{""} : rm.path();
				RequestMethod[] methods = rm.method().length == 0 ? RequestMethod.values() : rm.method();
				for (String prefix : prefixes) {
					for (String p : paths) {
						for (RequestMethod method : methods) {
							if (method == RequestMethod.TRACE) {
								continue;
							}
							endpoints.add(new Endpoint(method.name(), join(prefix, p), type.getSimpleName() + "#" + m.getName()));
						}
					}
				}
			}
		}
	}

	private static String join(String prefix, String p) {
		String s = ("/" + prefix + "/" + p).replaceAll("/{2,}", "/");
		return s.length() > 1 && s.endsWith("/") ? s.substring(0, s.length() - 1) : s;
	}

	/** 路径变量/通配替换为具体值，模拟真实请求 */
	private static String concrete(String path) {
		return path.replaceAll("\\{[^}]*}", "1").replace("**", "x").replace("*", "x");
	}

	/** 清单与数量只在默认 full 装配（gis/track/ai 都在类路径）下校验；-Pbasic 等裁剪装配跳过 */
	private static boolean fullAssembly() {
		for (String c : List.of("com.mugsun.boot.track.TrackAutoConfiguration", "com.mugsun.boot.ai.AiAutoConfiguration",
			"com.mugsun.boot.gis.GisAutoConfiguration")) {
			try {
				Class.forName(c, false, DemoReadOnlyEndpointsTest.class.getClassLoader());
			} catch (ClassNotFoundException e) {
				return false;
			}
		}
		return true;
	}

	@Test
	void scannerSeesTheWholeApp() {
		assumeTrue(fullAssembly(), "仅 full 装配校验");
		long writes = endpoints.stream().filter(e -> WRITE_METHODS.contains(e.method())).count();
		// 方案调研时约 300 个写映射；数量骤降说明扫描失效（测试就形同虚设）
		assertThat(writes).as("写映射数量").isGreaterThan(200);
	}

	@Test
	void everyWriteEndpointIsBlockedUnlessAllowlisted() {
		List<String> leaks = new ArrayList<>();
		for (Endpoint e : endpoints) {
			if (!WRITE_METHODS.contains(e.method())) {
				continue;
			}
			boolean allowed = POLICY.isAllowed(e.method(), concrete(e.path()), false);
			boolean expected = "POST".equals(e.method()) && POLICY.writeAllowlist().contains(e.path());
			if (allowed != expected) {
				leaks.add(e.method() + " " + e.path() + " (" + e.handler() + ") allowed=" + allowed);
			}
		}
		assertThat(leaks).as("演示模式写端点放行与白名单不一致").isEmpty();
	}

	@Test
	void allowlistOnlyNamesRealEndpoints() {
		Set<String> postPaths = new TreeSet<>();
		endpoints.stream().filter(e -> "POST".equals(e.method())).forEach(e -> postPaths.add(e.path()));
		assertThat(postPaths).as("白名单里每一项都必须对应真实的 POST 端点").containsAll(POLICY.writeAllowlist());
	}

	@Test
	void mustBlockList() {
		List<String> mustBlock = List.of(
			"POST /auth/register", "POST /auth/update-password", "POST /auth/forget-reset", "POST /auth/forget-code",
			"POST /auth/update-info", "POST /auth/update-avatar", "POST /auth/avatar/upload",
			"POST /auth/sms-code", "POST /auth/sms/send", "POST /auth/social/bind", "POST /auth/switch-tenant/000000",
			"POST /system/user/import", "POST /system/file/upload", "POST /system/oss/upload",
			"POST /system/param/submit", "POST /system/job/run/1", "POST /system/gen/syncDdl",
			"POST /system/ai/chat/stream", "POST /system/ai/weave/trackInsight", "POST /v1/chat/completions",
			"POST /track/visual/draft", "POST /system/track/visual/rule/submit",
			"POST /warm-flow/save-xml", "POST /actuator/shutdown", "POST /actuator/loggers/root",
			"PUT /auth/login", "DELETE /auth/login", "PATCH /track/collect",
			"GET /open/user/list", "GET /open/ping", "POST /open/echo",
			"GET /oauth2/authorize", "POST /oauth2/token", "GET /v1/models",
			"POST /system/crypto/echo", "GET /system/crypto/echo");
		for (String s : mustBlock) {
			String[] mp = s.split(" ");
			assertThat(POLICY.isAllowed(mp[0], mp[1], false)).as(s).isFalse();
		}
	}

	@Test
	void allowedReadsAndLogin() {
		for (String s : List.of("GET /auth/info", "GET /auth/captcha/config", "POST /auth/captcha/challenge",
			"POST /auth/captcha/verify", "GET /app/captcha/generate", "HEAD /system/user/list",
			"OPTIONS /system/user/submit", "GET /track/config", "POST /auth/login", "POST /auth/logout",
			"POST /app/captcha/check", "POST /track/collect", "POST /track/replay", "POST /track/api-body")) {
			String[] mp = s.split(" ");
			assertThat(POLICY.isAllowed(mp[0], mp[1], false)).as(s).isTrue();
		}
	}

	@Test
	void trackCollectCanBeSwitchedOff() {
		DemoReadOnlyPolicy off = new DemoReadOnlyPolicy(false);
		assertThat(off.isAllowed("POST", "/track/collect", false)).isFalse();
		assertThat(off.isAllowed("POST", "/auth/login", false)).isTrue();
	}

	@Test
	void pathTricksDoNotBypass() {
		for (String p : List.of("//auth//register", "/auth/register/", "/auth/register;jsessionid=1",
			"/auth/login/../register", "/auth/login;/../register", "/auth/login%2F..%2Fregister",
			"/auth/login%252F..%252Fregister", "/auth/login/./", "/auth\\login", "/auth/login%00",
			"/Auth/Login", "/AUTH/LOGIN", "/auth/login.json", "/auth/loginx")) {
			assertThat(POLICY.isAllowed("POST", p, false)).as(p).isFalse();
		}
		for (String p : List.of("//open/ping", "/OPEN/ping", "/open;x=1/ping", "/%6Fpen/ping", "/oauth2/", "/Oauth2/authorize")) {
			assertThat(POLICY.isAllowed("GET", p, false)).as(p).isFalse();
		}
		// 规范化后仍精确等于白名单的变体放行（与 Spring 路由一致，不扩大面）
		assertThat(POLICY.isAllowed("POST", "/auth/login/", false)).isTrue();
		assertThat(POLICY.isAllowed("POST", "//auth/login", false)).isTrue();
		assertThat(POLICY.isAllowed("post", "/auth/login", false)).isTrue();
		assertThat(POLICY.isAllowed("TRACE", "/auth/info", false)).isFalse();
		assertThat(POLICY.isAllowed("PROPFIND", "/auth/info", false)).isFalse();
	}

	@Test
	void filterRejectsWithDemoReadonlyBodyAndMethodOverride() throws Exception {
		DemoReadOnlyFilter filter = new DemoReadOnlyFilter(new ObjectMapper(), true);
		assertThat(new DemoReadOnlyFilter(new ObjectMapper(), false).policy().isAllowed("POST", "/track/collect", false))
			.as("track-collect 关闭时采集被拒").isFalse();

		MockHttpServletResponse res = run(filter, "POST", "/api", "/api/auth/register", null, null);
		assertThat(res.getStatus()).isEqualTo(403);
		String body = res.getContentAsString(StandardCharsets.UTF_8);
		assertThat(body).contains("演示环境只读").contains("\"errorCode\":\"DEMO_READONLY\"").contains("\"code\":403");

		assertThat(run(filter, "POST", "/api", "/api/auth/login", null, null).getStatus()).isEqualTo(200);
		assertThat(run(filter, "GET", "", "/system/user/list", "X-HTTP-Method-Override", null).getStatus()).isEqualTo(403);
		assertThat(run(filter, "POST", "", "/auth/login", "X-HTTP-Method", null).getStatus()).isEqualTo(403);
		assertThat(run(filter, "GET", "", "/system/user/list", null, "_method=DELETE").getStatus()).isEqualTo(403);
		assertThat(run(filter, "GET", "", "/system/user/list", null, "a=1&_method=PUT").getStatus()).isEqualTo(403);
		assertThat(run(filter, "GET", "", "/system/user/list", null, "a=1").getStatus()).isEqualTo(200);

		// 表单 body 里的 _method（F5）
		MockHttpServletRequest form = new MockHttpServletRequest("POST", "/auth/login");
		form.setContentType("application/x-www-form-urlencoded");
		form.addParameter("_method", "DELETE");
		MockHttpServletResponse formRes = new MockHttpServletResponse();
		filter.doFilter(form, formRes, new MockFilterChain());
		assertThat(formRes.getStatus()).isEqualTo(403);
	}

	/** 供运行时映射交叉核对测试复用：静态扫描得到的 (方法 路径) 集合 */
	static Set<String> staticMappings() throws Exception {
		if (endpoints == null) {
			scan();
		}
		Set<String> s = new TreeSet<>();
		endpoints.forEach(e -> s.add(e.method() + " " + e.path()));
		return s;
	}

	private static MockHttpServletResponse run(DemoReadOnlyFilter filter, String method, String ctx, String uri,
											   String header, String query) throws Exception {
		MockHttpServletRequest req = new MockHttpServletRequest(method, uri);
		req.setContextPath(ctx);
		if (header != null) {
			req.addHeader(header, "DELETE");
		}
		if (query != null) {
			req.setQueryString(query);
		}
		MockHttpServletResponse res = new MockHttpServletResponse();
		filter.doFilter(req, res, new MockFilterChain());
		return res;
	}

	@Test
	void cryptoDemoControllerAndFilterFollowDemoSwitch() {
		ApplicationContextRunner runner = new ApplicationContextRunner()
			.withBean(ObjectMapper.class)
			.withUserConfiguration(CryptoDemoController.class, DemoReadOnlyFilter.class);
		runner.withPropertyValues("mugsun.demo.enabled=true").run(ctx -> {
			assertThat(ctx).doesNotHaveBean(CryptoDemoController.class);
			assertThat(ctx).hasSingleBean(DemoReadOnlyFilter.class);
		});
		runner.run(ctx -> {
			assertThat(ctx).hasSingleBean(CryptoDemoController.class);
			assertThat(ctx).doesNotHaveBean(DemoReadOnlyFilter.class);
		});
	}

	@Test
	void endpointInventoryIsUpToDate() throws Exception {
		assumeTrue(fullAssembly(), "仅 full 装配校验");
		StringBuilder sb = new StringBuilder();
		sb.append("# 只读演示模式：写端点清单（自动生成，勿手改）\n\n")
			.append("由 `DemoReadOnlyEndpointsTest` 反射生成；代码与清单不一致时测试失败。重新生成：\n\n")
			.append("```bash\nmvn -pl mugsun-boot-server test -Dtest=DemoReadOnlyEndpointsTest -Ddemo.endpoints.write=true\n```\n\n")
			.append("GET/HEAD/OPTIONS 全部放行（`/open/**`、`/oauth2/**`、`/v1/**`、`/system/crypto/**`、`/auth/social/**`、`/system/ai/**` 除外，任何方法都拒；"
				+ "唯一例外是登录页加载要用的只读接口 `GET /auth/social/sources`，精确匹配）。\n")
			.append("下表只列写方法（POST/PUT/DELETE/PATCH）；「放行」即白名单，其余返回 403「演示环境只读」。\n\n")
			.append("| 方法 | 路径 | 处理器 | demo |\n|---|---|---|---|\n");
		endpoints.stream()
			.filter(e -> WRITE_METHODS.contains(e.method()))
			.sorted((a, b) -> (a.path() + " " + a.method() + " " + a.handler()).compareTo(b.path() + " " + b.method() + " " + b.handler()))
			.distinct()
			.forEach(e -> sb.append("| ").append(e.method()).append(" | `").append(e.path()).append("` | ")
				.append(e.handler()).append(" | ")
				.append(POLICY.isAllowed(e.method(), concrete(e.path()), false) ? "放行" : "拒绝").append(" |\n"));
		String generated = sb.toString();
		Files.createDirectories(Path.of("target"));
		Files.writeString(Path.of("target", "demo-readonly-endpoints.md"), generated);
		if (Boolean.getBoolean("demo.endpoints.write")) {
			Files.createDirectories(INVENTORY.getParent());
			Files.writeString(INVENTORY, generated);
		}
		assertThat(Files.exists(INVENTORY)).as(INVENTORY + " 不存在，用 -Ddemo.endpoints.write=true 生成").isTrue();
		assertThat(Files.readString(INVENTORY)).as("端点清单过期，用 -Ddemo.endpoints.write=true 重新生成").isEqualTo(generated);
	}
}
