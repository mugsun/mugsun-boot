package com.mugsun.boot.tenant;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 缓存键租户隔离回归：同一业务键在不同租户上下文下产出不同缓存键，杜绝跨租户命中串号。
 */
class TenantKeyConvertorTest {

	private final TenantKeyConvertor convertor = new TenantKeyConvertor();

	@AfterEach
	void tearDown() {
		TenantContext.clear();
	}

	/** 无上下文（平台级）前缀 {@code -}，与租户缓存自然分隔 */
	@Test
	void noContextPrefixed() {
		assertTrue(convertor.apply("code").toString().startsWith("-:"));
	}

	/** 各租户上下文前缀各自租户号 */
	@Test
	void tenantContextPrefixed() {
		String a = TenantContext.execute("000000", () -> convertor.apply("code")).toString();
		String b = TenantContext.execute("515537", () -> convertor.apply("code")).toString();
		assertTrue(a.startsWith("000000:"));
		assertTrue(b.startsWith("515537:"));
	}

	/** ③ 同一业务键跨租户产出不同缓存键 → 不串 */
	@Test
	void sameKeyDiffersAcrossTenants() {
		Object a = TenantContext.execute("000000", () -> convertor.apply("code"));
		Object b = TenantContext.execute("515537", () -> convertor.apply("code"));
		assertNotEquals(a, b);
	}
}
