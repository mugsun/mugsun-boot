package com.mugsun.boot.monitor;

import com.mugsun.boot.common.web.ClientIpResolver;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.servlet.HandlerMapping;

import static org.assertj.core.api.Assertions.assertThat;

class AccessLogFilterTest {

	@AfterEach
	void reset() {
		ClientIpResolver.setTrustedProxies("");
		MDC.clear();
	}

	@Test
	void lineHasFieldsButNoQueryOrBody() {
		ClientIpResolver.setTrustedProxies("127.0.0.0/8");
		MockHttpServletRequest req = new MockHttpServletRequest("GET", "/api/ws/message");
		req.setAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE, "/api/ws/message");
		req.setQueryString("token=SECRET");
		req.setRemoteAddr("127.0.0.1");
		req.addHeader("X-Forwarded-For", "203.0.113.7");
		req.setContent("{\"password\":\"p\"}".getBytes());
		MDC.put("traceId", "t-1");
		String line = AccessLogFilter.line(req, 101, 12, "42");
		assertThat(line).isEqualTo("ip=203.0.113.7 method=GET uri=/api/ws/message status=101 ms=12 user=42 trace=t-1");
		assertThat(line).doesNotContain("SECRET", "password");
	}

	@Test
	void controlCharactersCannotForgeLines() {
		assertThat(AccessLogFilter.clean("a\nb\r c\u0000")).isEqualTo("a_b__c_");
		assertThat(AccessLogFilter.clean(null)).isEqualTo("-");
		assertThat(AccessLogFilter.clean("x".repeat(600))).hasSize(512);
	}

	@Test
	void pathVariableRouteLogsTemplateNotRawUri() {
		MockHttpServletRequest req = new MockHttpServletRequest("GET",
			"/system/file/download/1843920012345678901");
		req.setAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE, "/system/file/download/{id}");
		String line = AccessLogFilter.line(req, 200, 3, null);
		assertThat(line).contains("uri=/system/file/download/{id}");
		assertThat(line).doesNotContain("1843920012345678901");
		assertThat(AccessLogFilter.routeTemplate(req)).isEqualTo("/system/file/download/{id}");
	}

	@Test
	void shareAndSignedRoutesLogTemplateNotToken() {
		String shareToken = "share-demo-token-001";
		MockHttpServletRequest share = new MockHttpServletRequest("GET", "/ai/share/" + shareToken);
		share.setAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE, "/ai/share/{token}");
		assertThat(AccessLogFilter.routeTemplate(share)).isEqualTo("/ai/share/{token}");
		assertThat(AccessLogFilter.line(share, 200, 1, null))
			.contains("uri=/ai/share/{token}")
			.doesNotContain(shareToken);

		String sig = "9f86d081884c7d659a2feaa0c55ad015a3bf4f1b2b0b822cd15d6c15b0f00a08";
		MockHttpServletRequest signed = new MockHttpServletRequest("GET", "/file/sig/" + sig + "/a.png");
		signed.setAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE, "/file/sig/{sig}/**");
		assertThat(AccessLogFilter.routeTemplate(signed)).isEqualTo("/file/sig/{sig}/**");
		assertThat(AccessLogFilter.line(signed, 200, 1, null))
			.contains("uri=/file/sig/{sig}/**")
			.doesNotContain(sig);
	}

	@Test
	void unmatchedRouteLogsFixedPlaceholderNotRawPath() {
		String secretPath = "/leak/token-XYZ9876543210abcdefghijklmnop";
		MockHttpServletRequest req = new MockHttpServletRequest("GET", secretPath);
		assertThat(AccessLogFilter.routeTemplate(req)).isEqualTo(AccessLogFilter.UNMATCHED);
		String line = AccessLogFilter.line(req, 404, 1, null);
		assertThat(line).contains("uri=(unmatched)");
		assertThat(line).doesNotContain("leak", "token-XYZ", secretPath);
		assertThat(AccessLogFilter.routeTemplate(new MockHttpServletRequest("GET", "/")))
			.isEqualTo(AccessLogFilter.UNMATCHED);
	}

	@Test
	void demoRetentionFloor() {
		assertThat(LogCleanJob.retentionFloor(30, true)).isEqualTo(180);
		assertThat(LogCleanJob.retentionFloor(365, true)).isEqualTo(365);
		assertThat(LogCleanJob.retentionFloor(30, false)).isEqualTo(30);
	}
}
