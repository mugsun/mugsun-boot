package com.mugsun.boot.ai.support;

import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class SqlTableGateTest {

	@Test
	void parseWhitelist() {
		assertEquals(Set.of("a", "b", "c"), SqlTableGate.parseWhitelist("a, b;C"));
		assertEquals(Set.of("public.t", "x"), SqlTableGate.parseWhitelist(" Public.T \n x "));
		assertTrue(SqlTableGate.parseWhitelist(null).isEmpty());
		assertTrue(SqlTableGate.parseWhitelist("  ").isEmpty());
	}
}
