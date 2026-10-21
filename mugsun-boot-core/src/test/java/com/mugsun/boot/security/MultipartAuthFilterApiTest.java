package com.mugsun.boot.security;

import com.mugsun.boot.AbstractIntegrationTest;
import cn.dev33.satoken.SaManager;
import cn.dev33.satoken.stp.StpUtil;
import jakarta.servlet.ServletContext;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.boot.web.servlet.ServletContextInitializer;
import org.springframework.boot.web.servlet.ServletContextInitializerBeans;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.Ordered;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.multipart.MultipartHttpServletRequest;
import org.springframework.web.multipart.MultipartResolver;
import org.springframework.web.servlet.DispatcherServlet;

import java.io.File;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 端到端复现 P3「匿名 multipart 预解析」：未登录的 multipart 在读请求体之前被拒，不触发 getParts、不写临时文件；
 * 登录后的正常上传和超限 400 保持不变；过滤器排在所有会读参数的 Filter 之前。
 * <p>复用基础上下文（另起上下文会撞 warm-flow 重复 Bean，见 RegisterOffApiTest）：用例前把 DispatcherServlet
 * 的 multipartResolver 换成计数包装，用例后换回。checkMultipart() 正是匿名预解析发生的地方。
 */
class MultipartAuthFilterApiTest extends AbstractIntegrationTest {

	static final AtomicInteger REACHED = new AtomicInteger();
	static final AtomicInteger GET_PARTS = new AtomicInteger();
	static final AtomicInteger TMP_FILES_SEEN = new AtomicInteger();

	@Autowired
	ConfigurableApplicationContext context;
	@Autowired
	MultipartEndpointAudit audit;
	@Autowired
	DispatcherServlet dispatcher;
	@Autowired
	ServletContext servletContext;

	private MultipartResolver original;

	/** Tomcat 在 location 为空时把 multipart 临时文件写到上下文临时目录，文件名 upload_*.tmp */
	int countTmp() {
		File dir = (File) servletContext.getAttribute(ServletContext.TEMPDIR);
		String[] names = dir == null ? null : dir.list((d, n) -> n.startsWith("upload_"));
		return names == null ? 0 : names.length;
	}

	@BeforeEach
	void installRecorder() {
		REACHED.set(0);
		GET_PARTS.set(0);
		TMP_FILES_SEEN.set(0);
		original = (MultipartResolver) ReflectionTestUtils.getField(dispatcher, "multipartResolver");
		MultipartResolver delegate = Objects.requireNonNull(original);
		ReflectionTestUtils.setField(dispatcher, "multipartResolver", new MultipartResolver() {
			@Override
			public boolean isMultipart(HttpServletRequest request) {
				boolean m = delegate.isMultipart(request);
				if (m) {
					REACHED.incrementAndGet();
				}
				return m;
			}

			@Override
			public MultipartHttpServletRequest resolveMultipart(HttpServletRequest request) {
				GET_PARTS.incrementAndGet();
				MultipartHttpServletRequest r = delegate.resolveMultipart(request);
				TMP_FILES_SEEN.addAndGet(countTmp());
				return r;
			}

			@Override
			public void cleanupMultipart(MultipartHttpServletRequest request) {
				delegate.cleanupMultipart(request);
			}
		});
	}

	@AfterEach
	void restoreResolver() {
		ReflectionTestUtils.setField(dispatcher, "multipartResolver", original);
	}

	private final HttpClient http = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build();

	private HttpResponse<String> send(String rawPath, String contentType, String token) throws Exception {
		String boundary = "mugsunBoundary";
		String body = "--" + boundary + "\r\n"
			+ "Content-Disposition: form-data; name=\"file\"; filename=\"a.txt\"\r\n"
			+ "Content-Type: text/plain\r\n\r\n"
			+ "x".repeat(64 * 1024) + "\r\n"
			+ "--" + boundary + "--\r\n";
		HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + rawPath))
			.header("Content-Type", contentType + "; boundary=" + boundary)
			.POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8));
		if (token != null) {
			b.header(TOKEN_HEADER, token);
		}
		return http.send(b.build(), HttpResponse.BodyHandlers.ofString());
	}

	private void assertNotParsed() {
		assertThat(REACHED.get()).as("没有到达 DispatcherServlet.checkMultipart").isZero();
		assertThat(GET_PARTS.get()).as("没有解析 multipart（getParts）").isZero();
		assertThat(countTmp()).as("没有残留临时文件").isZero();
	}

	@Test
	@DisplayName("未登录 multipart 到登录接口：415，不解析")
	void anonymousLoginMultipartIs415() throws Exception {
		HttpResponse<String> r = send("/auth/login", "multipart/form-data", null);
		assertNotParsed();
		assertThat(r.statusCode()).isEqualTo(415);
		assertThat(r.headers().firstValue("Connection")).hasValue("close");
	}

	@ParameterizedTest
	@ValueSource(strings = {"/system/file/upload", "/auth/avatar/upload", "/system/user/import",
		"/system/region/import"})
	@DisplayName("未登录 multipart 到上传接口：401，不解析")
	void anonymousUploadIs401(String path) throws Exception {
		HttpResponse<String> r = send(path, "multipart/form-data", null);
		assertNotParsed();
		assertThat(r.statusCode()).isEqualTo(401);
		assertThat(r.headers().firstValue("Connection")).hasValue("close");
	}

	@Test
	@DisplayName("伪造 token、multipart/mixed、大写 Content-Type 同样被拒")
	void forgedTokenAndContentTypeVariants() throws Exception {
		assertThat(send("/system/file/upload", "multipart/form-data", "not-a-real-token").statusCode())
			.isEqualTo(401);
		assertThat(send("/system/file/upload", "multipart/mixed", null).statusCode()).isEqualTo(401);
		assertThat(send("/auth/login", "MULTIPART/MIXED", null).statusCode()).isEqualTo(415);
		assertThat(send("/auth/login", "Multipart/Form-Data", null).statusCode()).isEqualTo(415);
		assertNotParsed();
	}

	@Test
	@DisplayName("白名单路径：被踢下线的 token 返回 401，不解析")
	void kickedOutTokenIs401() throws Exception {
		String token = loginAdmin();
		assertThat(send("/system/file/upload", "multipart/form-data", token).statusCode()).as("踢前可用").isEqualTo(200);
		reset();
		StpUtil.kickoutByTokenValue(token);
		HttpResponse<String> r = send("/system/file/upload", "multipart/form-data", token);
		assertNotParsed();
		assertThat(r.statusCode()).isEqualTo(401);
	}

	@Test
	@DisplayName("白名单路径：被顶下线的 token 返回 401，不解析")
	void replacedTokenIs401() throws Exception {
		String token = loginAdmin();
		StpUtil.replacedByTokenValue(token);
		HttpResponse<String> r = send("/system/file/upload", "multipart/form-data", token);
		assertNotParsed();
		assertThat(r.statusCode()).isEqualTo(401);
	}

	@Test
	@DisplayName("白名单路径：已过期的 token 返回 401，不解析")
	void expiredTokenIs401() throws Exception {
		String token = loginAdmin();
		// 把 token 映射的剩余有效期压到 1 秒，等 Redis 过期
		String key = StpUtil.getStpLogic().splicingKeyTokenValue(token);
		SaManager.getSaTokenDao().updateTimeout(key, 1);
		Thread.sleep(2000);
		HttpResponse<String> r = send("/system/file/upload", "multipart/form-data", token);
		assertNotParsed();
		assertThat(r.statusCode()).isEqualTo(401);
	}

	private void reset() {
		REACHED.set(0);
		GET_PARTS.set(0);
		TMP_FILES_SEEN.set(0);
	}

	@ParameterizedTest
	@ValueSource(strings = {"/system/file/upload/", "/system/file/upload;jsessionid=1", "/system;a=1/file/upload",
		"//system/file/upload", "/system//file/upload", "/System/File/Upload", "/system/file/uplo%61d"})
	@DisplayName("带合法 token 的绕过形式：415，不解析")
	void bypassFormsWithValidTokenAre415(String rawPath) throws Exception {
		String token = loginAdmin();
		HttpResponse<String> r = send(rawPath, "multipart/form-data", token);
		assertNotParsed();
		assertThat(r.statusCode()).as(rawPath).isEqualTo(415);
	}

	@Test
	@DisplayName("编码斜杠：容器先回 400 或过滤器回 415，都不解析")
	void encodedSlashRejected() throws Exception {
		String token = loginAdmin();
		HttpResponse<String> r = send("/system%2Ffile/upload", "multipart/form-data", token);
		assertThat(r.statusCode()).isIn(400, 415);
		assertNotParsed();
	}

	@Test
	@DisplayName("登录后正常上传不变：200，确实走了 multipart 解析并写过临时文件")
	void authenticatedUploadStillWorks() throws Exception {
		String token = loginAdmin();
		HttpResponse<String> r = send("/system/file/upload", "multipart/form-data", token);
		assertThat(r.statusCode()).isEqualTo(200);
		assertThat(om.readTree(r.body()).path("code").asInt()).as(r.body()).isEqualTo(200);
		assertThat(GET_PARTS.get()).as("对照组：正常上传会调用 getParts").isPositive();
		assertThat(TMP_FILES_SEEN.get()).as("对照组：正常上传会写临时文件，说明上面的临时目录断言有效")
			.isPositive();
	}

	@Test
	@DisplayName("登录后超出单文件上限（20MB）仍是 400 + 友好提示")
	void authenticatedOversizeStill400() throws Exception {
		String token = loginAdmin();
		String boundary = "mugsunBoundary";
		byte[] head = ("--" + boundary + "\r\nContent-Disposition: form-data; name=\"file\"; filename=\"big.txt\"\r\n"
			+ "Content-Type: text/plain\r\n\r\n").getBytes(StandardCharsets.US_ASCII);
		byte[] tail = ("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.US_ASCII);
		byte[] body = new byte[head.length + 20 * 1024 * 1024 + 256 * 1024 + tail.length];
		java.util.Arrays.fill(body, (byte) 'x');
		System.arraycopy(head, 0, body, 0, head.length);
		System.arraycopy(tail, 0, body, body.length - tail.length, tail.length);
		HttpRequest req = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/system/file/upload"))
			.header("Content-Type", "multipart/form-data; boundary=" + boundary)
			.header(TOKEN_HEADER, token)
			.POST(HttpRequest.BodyPublishers.ofByteArray(body)).build();
		HttpResponse<String> r = http.send(req, HttpResponse.BodyHandlers.ofString());
		assertThat(r.statusCode()).isEqualTo(400);
		assertThat(r.body()).contains("20MB");
	}

	@Test
	@DisplayName("过滤器顺序：MultipartAuthFilter 排在所有读参数 / 读请求体的 Filter 之前")
	void filterOrder() {
		List<String> order = new ArrayList<>();
		for (ServletContextInitializer init : new ServletContextInitializerBeans(context.getBeanFactory())) {
			if (init instanceof FilterRegistrationBean<?> frb) {
				order.add(Objects.requireNonNull(frb.getFilter()).getClass().getName());
			}
		}
		int me = order.indexOf(MultipartAuthFilter.class.getName());
		assertThat(me).as("已注册：" + order).isGreaterThanOrEqualTo(0);
		// 排在它前面的只允许 Spring Boot 的 CharacterEncodingFilter（同为 HIGHEST_PRECEDENCE，只设编码不读参数）
		assertThat(order.subList(0, me)).as("排在前面的过滤器：" + order)
			.allMatch(n -> n.endsWith("CharacterEncodingFilter"));
		for (Class<?> later : List.of(com.mugsun.boot.captcha.CaptchaBodyLimitFilter.class,
			com.mugsun.boot.monitor.TraceIdFilter.class, XssFilter.class,
			com.mugsun.boot.monitor.ApiLogFilter.class, com.mugsun.boot.monitor.ActuatorAuthFilter.class,
			com.mugsun.boot.system.file.LocalFileAccessFilter.class)) {
			assertThat(order.indexOf(later.getName())).as(later.getSimpleName() + " 在 " + order).isGreaterThan(me);
		}
		assertThat(MultipartAuthFilter.ORDER).isEqualTo(Ordered.HIGHEST_PRECEDENCE);
	}

	@Test
	@DisplayName("从注解推导的上传接口都在白名单里（core 单模块缺 sourcemap 接口）")
	void whitelistMatchesAnnotatedEndpoints() {
		assertThat(audit.multipartEndpoints())
			.containsExactlyInAnyOrder("/system/file/upload", "/auth/avatar/upload", "/system/user/import",
				"/system/region/import");
		assertThat(MultipartGuardProperties.DEFAULT_ALLOWED_PATHS).containsAll(audit.multipartEndpoints());
	}
}
