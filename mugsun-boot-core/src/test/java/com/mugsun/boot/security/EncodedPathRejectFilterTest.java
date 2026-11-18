package com.mugsun.boot.security;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.assertj.core.api.Assertions.assertThat;

class EncodedPathRejectFilterTest {

	private final EncodedPathRejectFilter filter = new EncodedPathRejectFilter();

	@ParameterizedTest
	@ValueSource(strings = {"/system/%74enant/list", "/%73ystem/tenant/list", "/system/tenant/%6Cist", "/fil%65/a.txt",
		"/system/user/%31", "/system/a%2Db", "/system/a%5fb", "/system/%7Euser", "/system/a%2eb"})
	void rejectsEncodedUnreservedCharacters(String uri) throws Exception {
		MockHttpServletRequest request = new MockHttpServletRequest("GET", uri);
		MockHttpServletResponse response = new MockHttpServletResponse();
		MockFilterChain chain = new MockFilterChain();

		filter.doFilter(request, response, chain);

		assertThat(response.getStatus()).isEqualTo(400);
		assertThat(response.getContentAsString()).contains("\"code\":400");
		assertThat(chain.getRequest()).isNull();
	}

	@ParameterizedTest
	@ValueSource(strings = {"/system/tenant/list", "/file/%E4%B8%AD%E6%96%87.png", "/system/a%20b", "/api/x%2Fy",
		"/system/user/100%", "/system/user/%4", "/system/user/%zz"})
	void passesPlainPathsAndOtherEncodings(String uri) throws Exception {
		MockHttpServletRequest request = new MockHttpServletRequest("GET", uri);
		MockHttpServletResponse response = new MockHttpServletResponse();
		MockFilterChain chain = new MockFilterChain();

		filter.doFilter(request, response, chain);

		assertThat(chain.getRequest()).isSameAs(request);
		assertThat(response.getStatus()).isEqualTo(200);
	}
}
