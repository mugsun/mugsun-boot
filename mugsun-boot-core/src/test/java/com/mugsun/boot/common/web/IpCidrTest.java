package com.mugsun.boot.common.web;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class IpCidrTest {

	private static boolean in(String rule, String ip) {
		IpCidr c = IpCidr.parse(rule);
		assertNotNull(c, rule);
		return c.contains(ip);
	}

	@Test
	void legacyWildcardMapsToPrefix() {
		assertTrue(in("10.*", "10.200.3.4"));
		assertFalse(in("10.*", "11.0.0.1"));
		assertTrue(in("10.1.*", "10.1.250.9"));
		assertFalse(in("10.1.*", "10.11.0.1"));
		assertTrue(in("1.2.3.*", "1.2.3.4"));
		assertFalse(in("1.2.3.*", "1.2.30.4"));
		assertTrue(in("10.*.*.*", "10.9.9.9"));
		assertTrue(in("10.1.*.*", "10.1.7.7"));
		assertFalse(in("10.1.*.*", "10.2.7.7"));
	}

	@Test
	void invalidWildcardIgnored() {
		assertNull(IpCidr.parse("10.*.1.*"));
		assertNull(IpCidr.parse("*.1.2.3"));
		assertNull(IpCidr.parse("*.*.*.*"));
		assertNull(IpCidr.parse("1.2.3.4.*"));
		assertNull(IpCidr.parse("300.*"));
		assertNull(IpCidr.parse("1.2*"));
		assertEquals(1, IpCidr.parseList("10.*.1.*, 1.2.3.*").size());
	}
}
