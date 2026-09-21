package com.mugsun.boot.tenant;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * 租户上下文隔离不变量回归测试：fail-closed、忽略语义、作用域可重入。
 * 纯逻辑（无 Web 上下文），覆盖 {@link TenantContext} 的 ThreadLocal 分支。
 */
class TenantContextTest {

	@AfterEach
	void tearDown() {
		TenantContext.clear();
	}

	/** ② 无任何租户上下文对租户表取条件 → fail-closed 抛异常，杜绝静默全量 */
	@Test
	void resolveWithoutContextFailsClosed() {
		assertThrows(TenantException.class, TenantContext::resolveTenantIds);
	}

	/** current() 无上下文返 null 且不抛，供缓存前缀安全读取 */
	@Test
	void currentWithoutContextReturnsNullWithoutThrowing() {
		assertNull(TenantContext.current());
	}

	/** 显式忽略作用域内不加租户条件（返 null），且不 fail-closed */
	@Test
	void ignoreScopeSkipsCondition() {
		Object[] ids = TenantContext.ignore(TenantContext::resolveTenantIds);
		assertNull(ids);
	}

	/** 指定租户作用域内按该租户取条件 */
	@Test
	void executeScopeResolvesGivenTenant() {
		Object[] ids = TenantContext.execute("515537", TenantContext::resolveTenantIds);
		assertArrayEquals(new Object[]{"515537"}, ids);
	}

	/** 查看全部哨兵不加条件 */
	@Test
	void allSentinelSkipsCondition() {
		assertNull(TenantContext.execute(TenantContext.ALL, TenantContext::resolveTenantIds));
	}

	/** ④ 可重入：外层指定租户内嵌套 ignore，内层退出后外层仍保持该租户（不被内层清除） */
	@Test
	void nestedIgnoreDoesNotLeakIntoOuterScope() {
		Object[] outer = TenantContext.execute("A", () -> {
			Object[] inner = TenantContext.ignore(TenantContext::resolveTenantIds);
			assertNull(inner);
			return TenantContext.resolveTenantIds();
		});
		assertArrayEquals(new Object[]{"A"}, outer);
	}

	/** 作用域退出后 ThreadLocal 归位，无残留串号 */
	@Test
	void scopeExitRestoresCleanState() {
		TenantContext.execute("A", () -> null);
		assertNull(TenantContext.getRaw());
		assertThrows(TenantException.class, TenantContext::resolveTenantIds);
	}
}
