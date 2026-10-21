package com.mugsun.boot.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.Part;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.io.BufferedReader;
import java.util.Collection;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/** 单测：拒绝路径上不碰请求体和参数（任何读取都会触发 Tomcat 的 multipart 解析） */
class MultipartAuthFilterTest {

	private final MultipartAuthFilter filter =
		new MultipartAuthFilter(new MultipartGuardProperties(), new ObjectMapper());

	/** 一旦读参数、读 parts 或调用 getInputStream / getReader 就直接失败 */
	static final class NoBodyRequest extends MockHttpServletRequest {
		NoBodyRequest(String method, String uri) {
			super(method, uri);
		}
		private static AssertionError touched(String what) {
			return new AssertionError("过滤器不应调用 " + what);
		}
		@Override public Collection<Part> getParts() { throw touched("getParts"); }
		@Override public Part getPart(String name) { throw touched("getPart"); }
		@Override public String getParameter(String name) { throw touched("getParameter"); }
		@Override public Map<String, String[]> getParameterMap() { throw touched("getParameterMap"); }
		@Override public String[] getParameterValues(String name) { throw touched("getParameterValues"); }
		@Override public java.util.Enumeration<String> getParameterNames() { throw touched("getParameterNames"); }
		@Override public ServletInputStream getInputStream() { throw touched("getInputStream"); }
		@Override public BufferedReader getReader() { throw touched("getReader"); }
	}

	private static NoBodyRequest multipart(String uri, String servletPath, String contentType) {
		NoBodyRequest req = new NoBodyRequest("POST", uri);
		req.setServletPath(servletPath);
		req.setContentType(contentType);
		req.setContent(new byte[1024]);
		return req;
	}

	private MockHttpServletResponse run(MockHttpServletRequest req, MockFilterChain chain) throws Exception {
		MockHttpServletResponse resp = new MockHttpServletResponse();
		filter.doFilter(req, resp, chain);
		return resp;
	}

	@ParameterizedTest
	@ValueSource(strings = {"multipart/form-data; boundary=x", "MULTIPART/FORM-DATA; boundary=x",
		"Multipart/Mixed; boundary=x", "  multipart/related; boundary=x"})
	@DisplayName("非白名单路径（登录接口）收到 multipart：415 + Connection: close，不调用 getInputStream / getParts")
	void nonWhitelistedIs415(String contentType) throws Exception {
		MockFilterChain chain = new MockFilterChain();
		MockHttpServletResponse resp = run(multipart("/auth/login", "/auth/login", contentType), chain);
		assertThat(resp.getStatus()).isEqualTo(415);
		assertThat(resp.getHeader("Connection")).isEqualTo("close");
		assertThat(chain.getRequest()).as("不进入后续过滤器").isNull();
	}

	@ParameterizedTest
	@ValueSource(strings = {"/system/file/upload", "/auth/avatar/upload", "/system/user/import",
		"/system/region/import", "/system/track/sourcemap/upload"})
	@DisplayName("白名单路径未带 token：401 + Connection: close，不调用 getInputStream / getParts")
	void whitelistedWithoutTokenIs401(String path) throws Exception {
		MockFilterChain chain = new MockFilterChain();
		MockHttpServletResponse resp = run(multipart(path, path, "multipart/form-data; boundary=x"), chain);
		assertThat(resp.getStatus()).isEqualTo(401);
		assertThat(resp.getHeader("Connection")).isEqualTo("close");
		assertThat(chain.getRequest()).isNull();
	}

	@Test
	@DisplayName("multipart/mixed 到白名单路径同样要求登录")
	void mixedOnWhitelistIs401() throws Exception {
		MockFilterChain chain = new MockFilterChain();
		MockHttpServletResponse resp = run(multipart("/system/file/upload", "/system/file/upload",
			"multipart/mixed; boundary=x"), chain);
		assertThat(resp.getStatus()).isEqualTo(401);
	}

	/** requestURI 是原文，servletPath 是容器规范化之后的值 */
	@ParameterizedTest(name = "{0}")
	@CsvSource({
		"末尾斜杠, /system/file/upload/, /system/file/upload/",
		";参数, /system/file/upload;jsessionid=1, /system/file/upload",
		"路径参数在中段, /system;x=1/file/upload, /system/file/upload",
		"编码斜杠, /system%2Ffile/upload, /system/file/upload",
		"编码字母, /system/file/uplo%61d, /system/file/upload",
		"双斜杠, //system/file/upload, /system/file/upload",
		"中段双斜杠, /system//file/upload, /system/file/upload",
		"大小写, /System/File/Upload, /System/File/Upload",
		"点段, /system/./file/upload, /system/file/upload",
	})
	@DisplayName("绕过形式一律按非白名单处理：415")
	void bypassFormsRejected(String name, String uri, String servletPath) throws Exception {
		MockFilterChain chain = new MockFilterChain();
		MockHttpServletResponse resp = run(multipart(uri, servletPath, "multipart/form-data; boundary=x"), chain);
		assertThat(resp.getStatus()).as(name).isEqualTo(415);
		assertThat(chain.getRequest()).isNull();
	}

	@Test
	@DisplayName("带 pathInfo 的请求不算精确命中")
	void pathInfoRejected() {
		MockHttpServletRequest req = new MockHttpServletRequest("POST", "/system/file/upload/x");
		req.setServletPath("/system/file/upload");
		req.setPathInfo("/x");
		assertThat(MultipartAuthFilter.isAllowedPath(req, Set.of("/system/file/upload"))).isFalse();
	}

	@Test
	@DisplayName("有 contextPath 时按 contextPath + servletPath 比对")
	void contextPathHonoured() {
		MockHttpServletRequest req = new MockHttpServletRequest("POST", "/api/system/file/upload");
		req.setContextPath("/api");
		req.setServletPath("/system/file/upload");
		assertThat(MultipartAuthFilter.isAllowedPath(req, Set.of("/system/file/upload"))).isTrue();
	}

	@Test
	@DisplayName("非 multipart 请求原样放行，不做任何判断")
	void nonMultipartPassesThrough() throws Exception {
		MockHttpServletRequest req = new MockHttpServletRequest("POST", "/auth/login");
		req.setServletPath("/auth/login");
		req.setContentType("application/json");
		MockFilterChain chain = new MockFilterChain();
		MockHttpServletResponse resp = run(req, chain);
		assertThat(resp.getStatus()).isEqualTo(200);
		assertThat(chain.getRequest()).isSameAs(req);
	}

	@ParameterizedTest
	@ValueSource(strings = {"multipart/form-data", "MULTIPART/MIXED", "multipart/x", " Multipart/Form-Data;b=1"})
	void contentTypeDetection(String ct) {
		assertThat(MultipartAuthFilter.isMultipart(ct)).isTrue();
	}

	@ParameterizedTest
	@ValueSource(strings = {"application/json", "text/plain", "application/x-www-form-urlencoded", "multipartx/y"})
	void notMultipart(String ct) {
		assertThat(MultipartAuthFilter.isMultipart(ct)).isFalse();
	}
}
