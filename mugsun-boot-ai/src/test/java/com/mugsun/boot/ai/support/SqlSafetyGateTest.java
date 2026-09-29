package com.mugsun.boot.ai.support;

import com.mugsun.core.tool.exception.ServiceException;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class SqlSafetyGateTest {

	@Test
	void allowsSelect() {
		assertEquals("select 1", SqlSafetyGate.guard("select 1"));
		assertTrue(SqlSafetyGate.guard("WITH a AS (SELECT 1) SELECT * FROM a").toLowerCase().startsWith("with"));
	}

	@Test
	void rejectsWrites() {
		assertThrows(ServiceException.class, () -> SqlSafetyGate.guard("delete from t"));
		assertThrows(ServiceException.class, () -> SqlSafetyGate.guard("update t set a=1"));
		assertThrows(ServiceException.class, () -> SqlSafetyGate.guard("insert into t values(1)"));
		assertThrows(ServiceException.class, () -> SqlSafetyGate.guard("drop table t"));
		assertThrows(ServiceException.class, () -> SqlSafetyGate.guard("select 1; delete from t"));
	}

	@Test
	void rejectsSelectIntoAndForUpdate() {
		assertThrows(ServiceException.class, () -> SqlSafetyGate.guard("select * into t2 from t"));
		assertThrows(ServiceException.class, () -> SqlSafetyGate.guard("SELECT id FROM t FOR UPDATE"));
	}

	@Test
	void rejectsCommentBypass() {
		assertThrows(ServiceException.class, () -> SqlSafetyGate.guard("select 1 /* */; drop table t"));
		assertThrows(ServiceException.class,
			() -> SqlSafetyGate.guard("select * from t --\ninto evil from t"));
	}
}
