package com.mugsun.boot.security;

import cn.dev33.satoken.SaManager;
import cn.dev33.satoken.stp.StpUtil;
import com.fasterxml.jackson.databind.JsonNode;
import com.mugsun.boot.AbstractIntegrationTest;
import com.mugsun.core.tool.api.ResultCode;
import jakarta.servlet.ServletContext;
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
import org.springframework.web.multipart.MultipartResolver;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;

import static com.mugsun.boot.security.MultipartResolverRecorder.PARSED;
import static com.mugsun.boot.security.MultipartResolverRecorder.REACHED;
import static com.mugsun.boot.security.MultipartResolverRecorder.TMP_FILES_SEEN;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * 端到端复现 P3「匿名 multipart 预解析」：未登录的 multipart 在读请求体之前被拒，不触发解析、不写临时文件；
 * 登录后的正常上传和超限 400 保持不变；过滤器排在所有会读参数的 Filter 之前。
 * <p>计数靠 {@link MultipartResolverRecorder}（被扫描进共享上下文的 BeanPostProcessor），每个用例独立，
 * 单独运行本类也成立；{@link #recorderIsActive} 与 {@link #authenticatedUploadStillWorks} 是正对照。
 */
class MultipartAuthFilterApiTest extends AbstractIntegrationTest {

	@Autowired
	ConfigurableApplicationContext context;
	@Autowired
	MultipartEndpointAudit audit;
	@Autowired
	ServletContext servletContext;
	@Autowired
	MultipartResolver multipartResolver;

	private final HttpClient http = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build();
	private int tmpBaseline;

	@BeforeEach
	void resetCounters() {
		MultipartResolverRecorder.tmpCounter = () -> MultipartResolverRecorder.countTmp(servletContext);
		MultipartResolverRecorder.reset();
		tmpBaseline = MultipartResolverRecorder.countTmp(servletContext);
	}

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
		assertThat(PARSED.get()).as("没有解析 multipart（getParts）").isZero();
		assertThat(MultipartResolverRecorder.countTmp(servletContext)).as("临时文件没有比用例开始时增加")
			.isLessThanOrEqualTo(tmpBaseline);
	}

	/** 状态码 + JSON 错误体 + Connection: close */
	private void assertRejected(HttpResponse<String> r, int status) throws Exception {
		assertThat(r.statusCode()).as(r.body()).isEqualTo(status);
		assertThat(r.headers().firstValue("Connection")).hasValue("close");
		assertThat(r.headers().firstValue("Content-Type")).hasValueSatisfying(
			ct -> assertThat(ct).startsWith("application/json"));
		JsonNode body = om.readTree(r.body());
		ResultCode code = status == 401 ? ResultCode.UNAUTHORIZED : ResultCode.FAILURE;
		assertThat(body.path("code").asInt()).isEqualTo(code.getCode());
		assertThat(body.path("success").asBoolean(true)).isFalse();
		assertThat(body.path("msg").asText()).isNotBlank();
	}

	@Test
	@DisplayName("正对照：计数包装已装进 DispatcherServlet 用的 multipartResolver")
	void recorderIsActive() {
		assertThat(multipartResolver).isInstanceOf(MultipartResolverRecorder.Counting.class);
	}

	@Test
	@DisplayName("未登录 multipart 到登录接口：415，不解析")
	void anonymousLoginMultipartIs415() throws Exception {
		HttpResponse<String> r = send("/auth/login", "multipart/form-data", null);
		assertNotParsed();
		assertRejected(r, 415);
	}

	@ParameterizedTest
	@ValueSource(strings = {"/system/file/upload", "/auth/avatar/upload", "/system/user/import",
		"/system/region/import"})
	@DisplayName("未登录 multipart 到上传接口：401，不解析")
	void anonymousUploadIs401(String path) throws Exception {
		HttpResponse<String> r = send(path, "multipart/form-data", null);
		assertNotParsed();
		assertRejected(r, 401);
	}

	@Test
	@DisplayName("伪造 token、multipart/mixed、大写 Content-Type 同样被拒")
	void forgedTokenAndContentTypeVariants() throws Exception {
		assertRejected(send("/system/file/upload", "multipart/form-data", "not-a-real-token"), 401);
		assertRejected(send("/system/file/upload", "multipart/mixed", null), 401);
		assertRejected(send("/auth/login", "MULTIPART/MIXED", null), 415);
		assertRejected(send("/auth/login", "Multipart/Form-Data", null), 415);
		assertNotParsed();
	}

	@Test
	@DisplayName("白名单路径：被踢下线的 token 返回 401，不解析")
	void kickedOutTokenIs401() throws Exception {
		String token = loginAdmin();
		StpUtil.kickoutByTokenValue(token);
		HttpResponse<String> r = send("/system/file/upload", "multipart/form-data", token);
		assertNotParsed();
		assertRejected(r, 401);
	}

	@Test
	@DisplayName("白名单路径：被顶下线的 token 返回 401，不解析")
	void replacedTokenIs401() throws Exception {
		String token = loginAdmin();
		StpUtil.replacedByTokenValue(token);
		HttpResponse<String> r = send("/system/file/upload", "multipart/form-data", token);
		assertNotParsed();
		assertRejected(r, 401);
	}

	@Test
	@DisplayName("白名单路径：已过期的 token 返回 401，不解析")
	void expiredTokenIs401() throws Exception {
		String token = loginAdmin();
		// 过期即 Redis 里的 token→loginId 映射消失；直接删 key，立即生效，不依赖时序
		String key = StpUtil.getStpLogic().splicingKeyTokenValue(token);
		SaManager.getSaTokenDao().delete(key);
		assertThat(SaManager.getSaTokenDao().get(key)).isNull();
		HttpResponse<String> r = send("/system/file/upload", "multipart/form-data", token);
		assertNotParsed();
		assertRejected(r, 401);
	}

	@ParameterizedTest
	@ValueSource(strings = {"/system/file/upload/", "/system/file/upload;jsessionid=1", "/system;a=1/file/upload",
		"//system/file/upload", "/system//file/upload", "/System/File/Upload", "/system/file/uplo%61d"})
	@DisplayName("带合法 token 的绕过形式：415，不解析")
	void bypassFormsWithValidTokenAre415(String rawPath) throws Exception {
		String token = loginAdmin();
		HttpResponse<String> r = send(rawPath, "multipart/form-data", token);
		assertNotParsed();
		assertRejected(r, 415);
	}

	@Test
	@DisplayName("编码斜杠：容器先回 400 或过滤器回 415，都不解析")
	void encodedSlashRejected() throws Exception {
		String token = loginAdmin();
		HttpResponse<String> r = send("/system%2Ffile/upload", "multipart/form-data", token);
		assertNotParsed();
		assertThat(r.statusCode()).isIn(400, 415);
	}

	@Test
	@DisplayName("正对照：登录后正常上传 200，确实走了 multipart 解析并写过临时文件")
	void authenticatedUploadStillWorks() throws Exception {
		String token = loginAdmin();
		HttpResponse<String> r = send("/system/file/upload", "multipart/form-data", token);
		assertThat(r.statusCode()).isEqualTo(200);
		assertThat(om.readTree(r.body()).path("code").asInt()).as(r.body()).isEqualTo(200);
		assertThat(REACHED.get()).as("正对照：到达 checkMultipart").isPositive();
		assertThat(PARSED.get()).as("正对照：正常上传会解析 multipart").isPositive();
		assertThat(TMP_FILES_SEEN.get()).as("正对照：正常上传会写临时文件，说明临时文件断言有效").isPositive();
	}

	@Test
	@DisplayName("登录后超出单文件上限（20MB）仍是 400 + 友好提示")
	void authenticatedOversizeStill400() throws Exception {
		String token = loginAdmin();
		byte[] body = multipartBody(20 * 1024 * 1024 + 256 * 1024);
		HttpRequest req = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/system/file/upload"))
			.header("Content-Type", "multipart/form-data; boundary=" + BOUNDARY)
			.header(TOKEN_HEADER, token)
			.POST(HttpRequest.BodyPublishers.ofByteArray(body)).build();
		HttpResponse<String> r = http.send(req, HttpResponse.BodyHandlers.ofString());
		assertThat(r.statusCode()).isEqualTo(400);
		assertThat(r.body()).contains("20MB");
	}

	static final String BOUNDARY = "mugsunBoundary";

	static byte[] multipartBody(int fileBytes) {
		byte[] head = ("--" + BOUNDARY + "\r\nContent-Disposition: form-data; name=\"file\"; filename=\"big.txt\"\r\n"
			+ "Content-Type: text/plain\r\n\r\n").getBytes(StandardCharsets.US_ASCII);
		byte[] tail = ("\r\n--" + BOUNDARY + "--\r\n").getBytes(StandardCharsets.US_ASCII);
		byte[] body = new byte[head.length + fileBytes + tail.length];
		Arrays.fill(body, (byte) 'x');
		System.arraycopy(head, 0, body, 0, head.length);
		System.arraycopy(tail, 0, body, body.length - tail.length, tail.length);
		return body;
	}

	/**
	 * 未登录约 25MB：用原始 socket 发，能区分「收到 401」和「连接被关闭」。
	 * 允许两种结果之一，但都必须没有解析、没有临时文件。
	 * 服务端是否读完请求体：记录客户端写出的字节数和是否在写的途中被对端关闭，只作参考——
	 * 内核 socket 缓冲有几 MB，写成功不等于服务端已读。
	 */
	@Test
	@DisplayName("未登录约 25MB multipart：401 或连接被关闭，不解析、不写临时文件")
	void anonymousLargeUploadRejectedWithoutParsing() throws Exception {
		byte[] body = multipartBody(25 * 1024 * 1024);
		String status = null;
		long written = 0;
		boolean writeAborted = false;
		boolean closedWithoutResponse = false;
		try (Socket s = new Socket()) {
			s.connect(new InetSocketAddress("127.0.0.1", port), 5000);
			s.setSoTimeout(15000);
			OutputStream out = s.getOutputStream();
			out.write(("POST /system/file/upload HTTP/1.1\r\nHost: 127.0.0.1:" + port + "\r\n"
				+ "Content-Type: multipart/form-data; boundary=" + BOUNDARY + "\r\n"
				+ "Content-Length: " + body.length + "\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
			try {
				for (int off = 0; off < body.length; off += 64 * 1024) {
					int len = Math.min(64 * 1024, body.length - off);
					out.write(body, off, len);
					written += len;
				}
				out.flush();
			} catch (IOException e) {
				writeAborted = true;
			}
			try {
				InputStream in = s.getInputStream();
				byte[] buf = in.readNBytes(12);
				String line = new String(buf, StandardCharsets.US_ASCII);
				if (line.startsWith("HTTP/1.1 ")) {
					status = line.substring(9).trim();
				} else {
					closedWithoutResponse = true;
				}
			} catch (IOException e) {
				closedWithoutResponse = true;
			}
		}
		System.out.printf("[25MB 匿名] status=%s written=%d/%d writeAborted=%s closedWithoutResponse=%s%n",
			status, written, body.length, writeAborted, closedWithoutResponse);
		if (status != null) {
			assertThat(status).isIn("401", "415");
		} else {
			assertThat(closedWithoutResponse).as("没有响应时必须是连接被关闭").isTrue();
		}
		assertNotParsed();
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
