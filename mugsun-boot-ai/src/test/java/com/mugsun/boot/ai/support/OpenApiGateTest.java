package com.mugsun.boot.ai.support;

import com.mugsun.core.tool.exception.ServiceException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import static org.junit.jupiter.api.Assertions.*;

class OpenApiGateTest {

	@AfterEach
	void reset() {
		OpenApiGate.setTrustedProxies("");
	}

	private static boolean allowed(String list, String ip) {
		try {
			OpenApiGate.checkAllowIps(list, ip);
			return true;
		} catch (ServiceException e) {
			return false;
		}
	}

	@Test
	void cidr24() {
		assertTrue(allowed("192.168.1.0/24", "192.168.1.200"));
		assertFalse(allowed("192.168.1.0/24", "192.168.2.1"));
	}

	@Test
	void cidr32AndSingleIp() {
		assertTrue(allowed("10.0.0.5/32", "10.0.0.5"));
		assertFalse(allowed("10.0.0.5/32", "10.0.0.6"));
		assertTrue(allowed("10.0.0.5", "10.0.0.5"));
		assertFalse(allowed("10.0.0.5", "10.0.0.50"));
	}

	@Test
	void cidr0() {
		assertTrue(allowed("0.0.0.0/0", "8.8.8.8"));
		assertFalse(allowed("0.0.0.0/0", "2001:db8::1"));
		assertTrue(allowed("::/0", "2001:db8::1"));
	}

	@Test
	void ipv6Slash64() {
		assertTrue(allowed("2001:db8:abcd:12::/64", "2001:db8:abcd:12:ffff::1"));
		assertFalse(allowed("2001:db8:abcd:12::/64", "2001:db8:abcd:13::1"));
	}

	@Test
	void noPrefixFalseMatch() {
		assertFalse(allowed("10.1.11.0/24", "10.1.1.1"));
		assertFalse(allowed("10.1.1.1", "10.1.1.10"));
		assertFalse(allowed("1.2.3.0/24", "1.2.30.4"));
	}

	@Test
	void invalidEntriesIgnored() {
		assertFalse(allowed("garbage, 10.0.0.0/33, 1.2.3.*", "1.2.3.4"));
		assertTrue(allowed("garbage, 10.0.0.0/8", "10.9.9.9"));
		assertFalse(allowed("10.0.0.0/8", "not-an-ip"));
	}

	@Test
	void xffIgnoredWhenRemoteNotTrusted() {
		MockHttpServletRequest r = new MockHttpServletRequest();
		r.setRemoteAddr("203.0.113.9");
		r.addHeader("X-Forwarded-For", "10.0.0.1");
		r.addHeader("X-Real-IP", "10.0.0.1");
		assertEquals("203.0.113.9", OpenApiGate.clientIp(r));
		OpenApiGate.setTrustedProxies("172.16.0.0/12");
		assertEquals("203.0.113.9", OpenApiGate.clientIp(r));
	}

	@Test
	void xffRightToLeftWhenTrusted() {
		OpenApiGate.setTrustedProxies("172.16.0.0/12, 10.0.0.0/8");
		MockHttpServletRequest r = new MockHttpServletRequest();
		r.setRemoteAddr("172.16.0.2");
		// 客户端伪造最左值 1.1.1.1；真实客户端 198.51.100.7；10.0.0.3 为内层代理
		r.addHeader("X-Forwarded-For", "1.1.1.1, 198.51.100.7, 10.0.0.3");
		assertEquals("198.51.100.7", OpenApiGate.clientIp(r));
	}

	@Test
	void xffAllTrustedOrInvalidFallsBack() {
		OpenApiGate.setTrustedProxies("10.0.0.0/8");
		MockHttpServletRequest r = new MockHttpServletRequest();
		r.setRemoteAddr("10.0.0.2");
		r.addHeader("X-Forwarded-For", "10.0.0.9");
		assertEquals("10.0.0.2", OpenApiGate.clientIp(r));
		MockHttpServletRequest r2 = new MockHttpServletRequest();
		r2.setRemoteAddr("10.0.0.2");
		r2.addHeader("X-Forwarded-For", "evil.example.com");
		assertEquals("10.0.0.2", OpenApiGate.clientIp(r2));
	}
}
