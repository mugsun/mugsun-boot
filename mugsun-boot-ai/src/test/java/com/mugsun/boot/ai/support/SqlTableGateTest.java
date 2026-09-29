package com.mugsun.boot.ai.support;

import com.mugsun.core.tool.exception.ServiceException;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class SqlTableGateTest {

	@Test
	void parseAndExtract() {
		assertEquals(Set.of("a", "b"), SqlTableGate.parseWhitelist("a, b;C".toLowerCase()));
		assertEquals(Set.of("users", "orders"),
			SqlTableGate.extractTables("SELECT * FROM users u JOIN orders o ON u.id=o.uid"));
	}

	@Test
	void assertAllowed() {
		assertDoesNotThrow(() -> SqlTableGate.assertAllowed("select * from users", Set.of("users")));
		assertThrows(ServiceException.class,
			() -> SqlTableGate.assertAllowed("select * from secrets", Set.of("users")));
		assertDoesNotThrow(() -> SqlTableGate.assertAllowed("select * from secrets", Set.of()));
	}
}
