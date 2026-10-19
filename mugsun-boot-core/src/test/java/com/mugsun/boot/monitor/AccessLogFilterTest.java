package com.mugsun.boot.monitor;

import com.mugsun.boot.common.web.ClientIpResolver;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.mock.web.MockHttpServletRequest;

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
	void pathDropsMatrixParamsAndTokenSegments() {
		assertThat(AccessLogFilter.redactPath("/api/file/download;jsessionid=ABC123")).isEqualTo("/api/file/download");
		assertThat(AccessLogFilter.redactPath("/file/sig/9f86d081884c7d659a2feaa0c55ad015a3bf4f1b2b0b822cd15d6c15b0f00a08/a.png"))
			.isEqualTo("/file/sig/:redacted/a.png");
		assertThat(AccessLogFilter.redactPath("/share/aB3dE5fG7hJ9kL1mN3pQ5rS7tU/view")).isEqualTo("/share/:redacted/view");
		assertThat(AccessLogFilter.redactPath("/system/user/page")).isEqualTo("/system/user/page");
		assertThat(AccessLogFilter.redactPath("/system/user/1843920012345678901")).isEqualTo("/system/user/1843920012345678901");
	}

	@Test
	void demoRetentionFloor() {
		assertThat(LogCleanJob.retentionFloor(30, true)).isEqualTo(180);
		assertThat(LogCleanJob.retentionFloor(365, true)).isEqualTo(365);
		assertThat(LogCleanJob.retentionFloor(30, false)).isEqualTo(30);
	}
}
