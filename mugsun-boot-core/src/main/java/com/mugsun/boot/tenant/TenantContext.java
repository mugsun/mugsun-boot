package com.mugsun.boot.tenant;

import cn.dev33.satoken.stp.StpUtil;
import com.mugsun.boot.common.constant.RoleConstants;
import com.mugsun.boot.common.constant.TenantConstants;

import java.util.function.Supplier;

/**
 * 租户上下文：统一的当前租户解析、作用域切换与忽略，供 {@link SaTokenTenantFactory} 取值。
 * <p>解析优先级：显式 ThreadLocal（异步透传 / execute / ignore）＞ Sa-Token 会话超管切换租户 ＞ 会话本租户。
 * <p>ThreadLocal 由 {@link TenantTaskDecorator} 在 @Async/虚拟线程间捕获-恢复，严格 finally remove。
 * <p>忽略与恢复采用「存旧值→set→finally 恢复」，天然可重入；与数据权限（G6 拦截器 + 手工条件）相互正交，忽略租户不误伤数据权限或防攻击拦截。
 * <p>{@link #ignore} 为唯一忽略入口（中心化忽略助手），替代散落的 Flex {@code withoutTenantCondition}。
 */
public final class TenantContext {

	/** 会话中保存本租户编号的键 */
	public static final String TENANT_SESSION_KEY = "tenantId";
	/** 超管切换租户会话键；值 -1 表示查看全部租户 */
	public static final String SWITCH_KEY = "switchTenant";
	/** 查看全部租户哨兵（不施加隔离） */
	public static final String ALL = "-1";
	/** 显式忽略租户哨兵（作用域内不施加隔离） */
	public static final String IGNORE = "";

	/** resolve() 返回此哨兵表示「无任何租户上下文」，区别于「已授权的不加条件(null)」 */
	private static final Object[] NO_CONTEXT = new Object[0];

	private static final ThreadLocal<String> HOLDER = new ThreadLocal<>();

	private TenantContext() {
	}

	public static void set(String tenantId) {
		HOLDER.set(tenantId);
	}

	public static String getRaw() {
		return HOLDER.get();
	}

	public static void clear() {
		HOLDER.remove();
	}

	/**
	 * 统一解析：{@code null}=不加条件（忽略/查看全部，已授权）；长度1数组=指定租户；
	 * {@link #NO_CONTEXT}=无任何上下文。
	 */
	private static Object[] resolve() {
		String raw = HOLDER.get();
		if (raw != null) {
			return (raw.isBlank() || ALL.equals(raw)) ? null : new Object[]{raw};
		}
		try {
			if (StpUtil.isLogin()) {
				Object sw = StpUtil.getSession().get(SWITCH_KEY);
				if (sw != null) {
					String s = sw.toString();
					return (ALL.equals(s) || s.isBlank()) ? null : new Object[]{s};
				}
				Object t = StpUtil.getSession().get(TENANT_SESSION_KEY);
				if (t != null && !t.toString().isBlank()) {
					return new Object[]{t.toString()};
				}
			}
		} catch (Exception ignored) {
			// 无会话上下文（异步/初始化）——落到 NO_CONTEXT
		}
		return NO_CONTEXT;
	}

	/** 当前有效租户（不抛）；{@code null} 表示不加租户条件或无上下文。供缓存前缀/日志读取。 */
	public static String current() {
		Object[] r = resolve();
		return (r == null || r.length == 0) ? null : r[0].toString();
	}

	/**
	 * 是否平台超管：会话租户为平台租户 {@code 000000} <b>且</b>持内置 {@code admin} 角色。
	 * <p>刻意不以「会话租户==000000」等价超管——自助注册用户亦落 000000 但无任何角色，
	 * 仅凭租户归属会把其误判为超管；而 {@code admin} 角色每租户各有一个，仅凭角色又会把各租户管理员误判为平台超管。
	 * 二者取交集方为唯一平台超管（平台租户下的内置管理员），用于平台专属路径与跨租户切换的门控。
	 */
	public static boolean isPlatformSuperAdmin() {
		try {
			if (!StpUtil.isLogin()) {
				return false;
			}
			Object t = StpUtil.getSession().get(TENANT_SESSION_KEY);
			if (t == null || !TenantConstants.DEFAULT_TENANT_ID.equals(t.toString())) {
				return false;
			}
			return StpUtil.getRoleList().contains(RoleConstants.ADMIN);
		} catch (Exception ignored) {
			return false;
		}
	}

	/**
	 * 供 {@link SaTokenTenantFactory} 取租户条件：{@code null}=不加条件（忽略/查看全部）；
	 * 长度1数组=按该租户隔离；无任何上下文则 fail-closed 抛 {@link TenantException}。
	 */
	public static Object[] resolveTenantIds() {
		Object[] r = resolve();
		if (r == NO_CONTEXT) {
			throw new TenantException("缺少租户上下文，禁止对租户表执行无隔离操作（须显式 TenantContext.ignore 或建立会话）");
		}
		return r;
	}

	/** 作用域执行：临时切到指定租户，存旧值→set→finally 恢复（可重入） */
	public static <T> T execute(String tenantId, Supplier<T> supplier) {
		String prev = HOLDER.get();
		HOLDER.set(tenantId);
		try {
			return supplier.get();
		} finally {
			restore(prev);
		}
	}

	public static void execute(String tenantId, Runnable runnable) {
		execute(tenantId, () -> {
			runnable.run();
			return null;
		});
	}

	/** 作用域忽略租户（唯一忽略入口，current() 返 null 即不隔离），可重入、不误伤数据权限 */
	public static <T> T ignore(Supplier<T> supplier) {
		return execute(IGNORE, supplier);
	}

	public static void ignore(Runnable runnable) {
		execute(IGNORE, () -> {
			runnable.run();
			return null;
		});
	}

	private static void restore(String prev) {
		if (prev == null) {
			HOLDER.remove();
		} else {
			HOLDER.set(prev);
		}
	}
}
