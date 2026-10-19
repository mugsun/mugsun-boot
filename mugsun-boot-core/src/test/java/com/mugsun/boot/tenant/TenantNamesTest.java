package com.mugsun.boot.tenant;

import com.mybatisflex.core.row.Row;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TenantNamesTest {

	@Test
	void sqlUsesQuotedLowercaseAliases() {
		String sql = TenantNames.nameSql("mugsun.sys_tenant");
		assertTrue(sql.contains("tenant_code AS \"tenant_code\""), sql);
		assertTrue(sql.contains("tenant_name AS \"tenant_name\""), sql);
		assertTrue(sql.contains("FROM mugsun.sys_tenant "), sql);
	}

	@Test
	void uppercaseKeysAreNotReadWithoutAlias() {
		// 达梦不加别名时返回大写列名：Row.get 区分大小写，旧实现会取到 null
		Row upper = new Row();
		upper.set("TENANT_CODE", "000000");
		upper.set("TENANT_NAME", "管理组");
		assertTrue(TenantNames.toNameMap(List.of(upper)).isEmpty());
	}

	@Test
	void aliasedRowsMapCodeToTrimmedName() {
		Row a = new Row();
		a.set("tenant_code", "000000");
		a.set("tenant_name", " 管理组 ");
		Row blank = new Row();
		blank.set("tenant_code", "100001");
		blank.set("tenant_name", " ");
		assertEquals(Map.of("000000", "管理组"), TenantNames.toNameMap(List.of(a, blank)));
	}
}
