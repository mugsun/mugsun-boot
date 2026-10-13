package com.mugsun.boot.system.service;

import com.mugsun.boot.common.web.ClientIpResolver;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ApiKeyCallRecorderIpTest {

	@AfterEach
	void reset() {
		ClientIpResolver.setTrustedProxies("");
	}

	@Test
	void xffIgnoredWhenRemoteNotTrusted() {
		MockHttpServletRequest r = new MockHttpServletRequest();
		r.setRemoteAddr("203.0.113.9");
		r.addHeader("X-Forwarded-For", "1.1.1.1");
		assertEquals("203.0.113.9", ApiKeyCallRecorder.ipOf(r));
		ClientIpResolver.setTrustedProxies("10.0.0.0/8");
		assertEquals("203.0.113.9", ApiKeyCallRecorder.ipOf(r));
	}

	@Test
	void realIpFromXffWhenRemoteTrusted() {
		ClientIpResolver.setTrustedProxies("10.0.0.0/8");
		MockHttpServletRequest r = new MockHttpServletRequest();
		r.setRemoteAddr("10.0.0.2");
		r.addHeader("X-Forwarded-For", "1.1.1.1, 198.51.100.7, 10.0.0.3");
		assertEquals("198.51.100.7", ApiKeyCallRecorder.ipOf(r));
	}
}
