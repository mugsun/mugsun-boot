package com.mugsun.boot.auth;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 国密关闭时：生产只接受 isSecure() 的请求（HTTPS，或经 forward-headers-strategy 采信的受信代理）；
 * 单纯带 X-Forwarded-Proto 头不算；开发环境放行。
 */
class PlaintextPasswordTlsGateTest {

	@AfterEach
	void clear() {
		RequestContextHolder.resetRequestAttributes();
	}

	private static PlaintextPasswordTlsGate gate(String profile) {
		MockEnvironment env = new MockEnvironment();
		if (profile != null) {
			env.setActiveProfiles(profile);
		}
		return new PlaintextPasswordTlsGate(env);
	}

	private static void bind(boolean secure, String forwardedProto) {
		MockHttpServletRequest req = new MockHttpServletRequest("POST", "/auth/login");
		req.setSecure(secure);
		if (forwardedProto != null) {
			req.addHeader("X-Forwarded-Proto", forwardedProto);
		}
		RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(req));
	}

	@Test
	void prodHttpRefused() {
		bind(false, null);
		assertThatThrownBy(() -> gate("prod").requireTls())
			.isInstanceOf(PlaintextPasswordTlsGate.InsecureTransportException.class)
			.hasMessageContaining("server.forward-headers-strategy");
	}

	@Test
	void prodSpoofedForwardedProtoRefused() {
		bind(false, "https");
		assertThatThrownBy(() -> gate("prod").requireTls())
			.isInstanceOf(PlaintextPasswordTlsGate.InsecureTransportException.class);
	}

	@Test
	void noProfileHttpRefused() {
		bind(false, null);
		assertThatThrownBy(() -> gate(null).requireTls())
			.isInstanceOf(PlaintextPasswordTlsGate.InsecureTransportException.class);
	}

	@Test
	void prodWithoutRequestRefused() {
		assertThatThrownBy(() -> gate("prod").requireTls())
			.isInstanceOf(PlaintextPasswordTlsGate.InsecureTransportException.class);
	}

	@Test
	void prodHttpsAllowed() {
		bind(true, null);
		assertThatCode(() -> gate("prod").requireTls()).doesNotThrowAnyException();
	}

	@Test
	void devHttpAllowed() {
		bind(false, null);
		assertThatCode(() -> gate("dev").requireTls()).doesNotThrowAnyException();
	}
}
