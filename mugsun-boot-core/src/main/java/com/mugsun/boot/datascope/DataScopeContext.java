package com.mugsun.boot.datascope;

import java.util.List;
import java.util.Set;
import java.util.function.Supplier;

/**
 * 数据权限上下文（请求级 ThreadLocal）：由 {@link DataScopeAspect} 在标注 {@link DataScope} 的方法上
 * <b>预解析</b>当前用户的全部角色数据范围后激活，供 {@link MugsunDataScopeDialect} 在 SQL 生成期读取注入。
 * <p>所有部门/子树/自定义部门/自定义SQL 均在切面激活<b>前</b>解析完毕存入 {@link Ctx}——方言钩子只读上下文纯拼条件、
 * 绝不查库（避免 SQL 生成期递归查询）。多角色多范围在方言处求 OR 并集。
 * <p>{@link #without} 提供旁路开关（超管/内部查询/自身解析），作用域内不注入；语义照 Flex 租户忽略范式。
 */
public final class DataScopeContext {

	/**
	 * 预解析的数据权限上下文。
	 *
	 * @param userId         当前用户
	 * @param deptId         当前用户部门
	 * @param scopes         用户全部角色的<b>去重</b>数据范围（1全部/2本部门/3本部门及子/4仅本人/5自定义部门/6自定义SQL）
	 * @param customDeptIds  自定义部门（scope=5）可见部门集合，预解析自 sys_role_dept
	 * @param subtreeDeptIds 本部门及子（scope=3）子树部门集合，预解析
	 * @param customSqls     自定义 SQL（scope=6）片段集合，预解析自 sys_role.custom_sql
	 */
	public record Ctx(Long userId, Long deptId, Set<Integer> scopes,
					  List<Long> customDeptIds, List<Long> subtreeDeptIds, List<String> customSqls) {
	}

	private static final ThreadLocal<Ctx> HOLDER = new ThreadLocal<>();
	private static final ThreadLocal<Integer> IGNORE = ThreadLocal.withInitial(() -> 0);

	private DataScopeContext() {
	}

	/** 激活数据权限（@DataScope 方法内生效） */
	public static void activate(Ctx ctx) {
		HOLDER.set(ctx);
	}

	public static Ctx current() {
		return HOLDER.get();
	}

	/** 是否处于生效状态：已激活且未被旁路忽略 */
	public static boolean isActive() {
		return HOLDER.get() != null && IGNORE.get() == 0;
	}

	public static void clear() {
		HOLDER.remove();
	}

	/** 作用域旁路（可重入）：内部忽略数据权限注入，用于超管/上下文自身解析/内部查询 */
	public static <T> T without(Supplier<T> supplier) {
		IGNORE.set(IGNORE.get() + 1);
		try {
			return supplier.get();
		} finally {
			IGNORE.set(IGNORE.get() - 1);
		}
	}

	public static void without(Runnable runnable) {
		without(() -> {
			runnable.run();
			return null;
		});
	}
}
