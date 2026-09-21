package com.mugsun.boot.common.mask;

import java.util.List;
import java.util.function.Supplier;

/**
 * 字段脱敏上下文：请求维度缓存当前登录用户的权限码快照，供脱敏处理器在结果集映射时读取。
 * <p>快照在拦截器 preHandle 一次性解析（早于业务查询），避免在结果集映射过程中再触发权限查询造成嵌套查询。
 * 无快照（未登录 / 非受控路径 / 异步内部读）时脱敏处理器按 fail-closed 处理（列不可见）。
 * <p>另提供{@link #maskedRead 恒脱敏读}：审计快照等内部读需与操作者角色无关地恒取脱敏值（不落明文、不置空）。
 */
public final class FieldMaskContext {

	private static final ThreadLocal<List<String>> HOLDER = new ThreadLocal<>();
	private static final ThreadLocal<Boolean> MASK_ONLY = new ThreadLocal<>();

	private FieldMaskContext() {
	}

	/** 缓存当前用户权限码快照 */
	public static void resolve(List<String> permissions) {
		HOLDER.set(permissions);
	}

	/** 读取权限码快照，未解析返回 null */
	public static List<String> current() {
		return HOLDER.get();
	}

	/** 清理快照，防止线程复用泄漏 */
	public static void clear() {
		HOLDER.remove();
	}

	/** 是否处于恒脱敏读模式 */
	public static boolean isMaskOnly() {
		return Boolean.TRUE.equals(MASK_ONLY.get());
	}

	/** 恒脱敏读：作用域内所有受控字段恒取脱敏值（供审计快照等内部读，与角色无关、不落明文） */
	public static <T> T maskedRead(Supplier<T> supplier) {
		Boolean previous = MASK_ONLY.get();
		MASK_ONLY.set(Boolean.TRUE);
		try {
			return supplier.get();
		} finally {
			if (previous == null) {
				MASK_ONLY.remove();
			} else {
				MASK_ONLY.set(previous);
			}
		}
	}
}
