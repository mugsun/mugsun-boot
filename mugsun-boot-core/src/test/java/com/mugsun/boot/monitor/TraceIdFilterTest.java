package com.mugsun.boot.monitor;

import com.mugsun.boot.common.constant.MonitorConstants;
import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.slf4j.MDC;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/** #98：客户端 X-Trace-Id 仅接受 [A-Za-z0-9-]{1,64}，非法入参不进 MDC/响应头 */
class TraceIdFilterTest {

	private static final String SIMPLE_UUID = "[0-9a-f]{32}";

	private final TraceIdFilter filter = new TraceIdFilter();

	@AfterEach
	void clearMdc() {
		MDC.clear();
	}

	private record Observed(String header, String mdcDuring, String mdcAfter) {
	}

	private Observed run(String clientTraceId) throws Exception {
		MockHttpServletRequest req = new MockHttpServletRequest("GET", "/any");
		if (clientTraceId != null) {
			req.addHeader(MonitorConstants.TRACE_HEADER, clientTraceId);
		}
		MockHttpServletResponse resp = new MockHttpServletResponse();
		AtomicReference<String> mdcDuring = new AtomicReference<>();
		FilterChain chain = (request, response) -> mdcDuring.set(MDC.get(MonitorConstants.TRACE_MDC_KEY));
		filter.doFilter(req, resp, chain);
		return new Observed(
			resp.getHeader(MonitorConstants.TRACE_HEADER),
			mdcDuring.get(),
			MDC.get(MonitorConstants.TRACE_MDC_KEY));
	}

	private void assertRegenerated(Observed o, String illegalInput) {
		assertThat(o.header()).isNotNull().matches(SIMPLE_UUID);
		assertThat(o.mdcDuring()).isEqualTo(o.header());
		assertThat(o.mdcAfter()).as("finally 须清 MDC").isNull();
		if (illegalInput != null && !illegalInput.isBlank()) {
			assertThat(o.header()).isNotEqualTo(illegalInput);
			assertThat(o.header()).doesNotContain("\n", "\r", "\u0000");
			assertThat(o.mdcDuring()).doesNotContain("\n", "\r", "\u0000");
		}
	}

	@ParameterizedTest(name = "非法=[{0}] → 新生 UUID")
	@ValueSource(strings = {
		"evil\ninject",
		"evil\rinject",
		"has\u0000null",
		"has\u0001ctrl",
		"中文trace",
		"abc_def",
		"space id",
		"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
	})
	@DisplayName("换行/控制字符/非 ASCII/非法字符/超长 → 丢弃并重生")
	void illegalHeaderIsRegenerated(String illegal) throws Exception {
		assertRegenerated(run(illegal), illegal);
	}

	@ParameterizedTest
	@NullAndEmptySource
	@ValueSource(strings = {"   ", "\t"})
	@DisplayName("缺省/空白 → 生成")
	void missingOrBlankGenerates(String header) throws Exception {
		assertRegenerated(run(header), header);
	}

	@Test
	@DisplayName("合法 id（字母数字连字符）原样回写响应头与 MDC")
	void legalIdIsPreserved() throws Exception {
		String legal = "abc-DEF-012";
		Observed o = run(legal);
		assertThat(o.header()).isEqualTo(legal);
		assertThat(o.mdcDuring()).isEqualTo(legal);
		assertThat(o.mdcAfter()).isNull();
	}

	@Test
	@DisplayName("合法 32 位 hex（fastSimpleUUID 形态）原样沿用")
	void legalSimpleUuidIsPreserved() throws Exception {
		String legal = "0123456789abcdef0123456789abcdef";
		Observed o = run(legal);
		assertThat(o.header()).isEqualTo(legal);
		assertThat(o.mdcDuring()).isEqualTo(legal);
		assertThat(o.mdcAfter()).isNull();
	}

	@Test
	@DisplayName("恰好 64 位合法字符可沿用")
	void legalMaxLengthIsPreserved() throws Exception {
		String legal = "A".repeat(64);
		Observed o = run(legal);
		assertThat(o.header()).isEqualTo(legal);
		assertThat(o.mdcDuring()).isEqualTo(legal);
	}
}
