package com.mugsun.boot.tenant;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 租户套餐「功能模块」注册表：把请求路径映射到套餐可控模块（前端路由 name），供服务端按套餐动态强制访问。
 *
 * <p>设计取向（架构师决策）：mugsun 套餐 {@code menu_keys} 存的是前端路由 name、且租户管理员持通配 {@code *}，
 * 因此「套餐→role_menu 授权物化」的做法无法约束通配管理员；改采「请求期动态强制」——
 * 每请求按当前套餐即时判定，套餐变更即时生效、天然无授权漂移。
 *
 * <p>匹配规则（段感知，避免 {@code /system/dict} 误伤 {@code /system/dict-biz}）：
 * 路径命中前缀 P 当且仅当 {@code path.equals(P) || path.startsWith(P + "/")}。
 *
 * <p>豁免（不受套餐限制，防误伤）：① 跨模块只读助手后缀（选择器/字典运行时）；
 * ② 个人域与共享聚合读（工作台/顶栏/个人通知消息），此类为登录用户自有数据或平台公共内容，非「功能模块」。
 */
public final class TenantPackageModules {

	/** 受控功能模块：API 路径前缀 → 授权该前缀的前端路由 name 集合（任一在套餐内即放行） */
	private static final Map<String, Set<String>> GATED = new java.util.LinkedHashMap<>();

	static {
		GATED.put("/system/user", Set.of("User"));
		GATED.put("/system/role", Set.of("Role"));
		GATED.put("/system/menu", Set.of("Menus"));
		GATED.put("/system/dept", Set.of("Dept"));
		GATED.put("/system/post", Set.of("Post"));
		GATED.put("/system/param", Set.of("SysParam"));
		GATED.put("/system/dict-biz", Set.of("DictBiz"));
		GATED.put("/system/dict", Set.of("Dict"));
		GATED.put("/system/notice", Set.of("Notice"));
		GATED.put("/system/oss", Set.of("Oss"));
		GATED.put("/system/sms", Set.of("Sms"));
		GATED.put("/system/gen", Set.of("Gen"));
		GATED.put("/system/form", Set.of("FormDesigner"));
		GATED.put("/system/flow", Set.of("FlowDef", "FlowTodo"));
		GATED.put("/system/job", Set.of("Job"));
		GATED.put("/system/report", Set.of("Report"));
		GATED.put("/system/region", Set.of("Region"));
		GATED.put("/system/login-log", Set.of("LoginLog"));
		GATED.put("/system/oper-log", Set.of("OperLog"));
		GATED.put("/system/data-audit", Set.of("DataAudit"));
		GATED.put("/system/help", Set.of("HelpDoc"));
		GATED.put("/system/changelog", Set.of("ChangeLog"));
		GATED.put("/system/feedback", Set.of("Feedback"));
		GATED.put("/system/message-template", Set.of("MessageTemplate"));
		GATED.put("/system/message", Set.of("MessageSend"));
		GATED.put("/system/mail-template", Set.of("MailTemplate"));
		GATED.put("/system/client", Set.of("Client"));
		GATED.put("/system/gis", Set.of("GisWorkspace", "GisProvider", "GisLayer", "GisScene", "GisAnalyze"));
	}

	/** 跨模块只读助手后缀（选择器 / 字典运行时批量），永远放行——套餐外模块仍可作只读选择器数据源 */
	private static final Set<String> HELPER_SUFFIXES = Set.of("select", "code-select", "batch", "tree", "lazy-tree");

	/** 个人域 / 共享聚合读前缀（工作台/顶栏/个人收件箱），登录用户自有数据或平台公共内容，不受套餐限制。
	 *  个人页（我的通知/我的消息）路由名不并入管理网关授权集，其个人端点一律在此豁免，杜绝「给个人页=解锁管理接口」。 */
	private static final List<String> SHARED_PREFIXES = List.of(
		"/system/workbench",
		"/system/changelog/recent",
		"/system/flow/my-todo",
		"/system/flow/history",
		"/system/notice/my",
		"/system/notice/read",
		"/system/feedback/submit",
		"/system/message/my",
		"/system/message/recent",
		"/system/message/unread-count",
		"/system/message/read",
		"/system/message/read-all",
		"/system/message/remove"
	);

	/**
	 * 平台超管专属路径（全方法）：平台级基础设施与全局配置的管理面，非平台超管访问一律拒绝。
	 * 判定依据：表无 tenant_id（平台全局）或资源属平台基础设施（会话/存储/缓存/调度/密钥），
	 * 租户管理员持通配 {@code *} 亦不得触达（生产安全红线，对抗审查实证类）。
	 */
	private static final List<String> PLATFORM_ONLY_PREFIXES = List.of(
		"/system/tenant",
		"/system/tenant-package",
		"/system/tenant-datasource",
		"/system/monitor",
		// 平台基础设施
		"/system/online",
		"/system/oss",
		"/system/cache",
		"/system/job",
		"/system/api-key",
		"/system/client",
		"/system/sms",
		// 平台全局配置（表无 tenant_id）
		"/system/notify",
		"/system/mail-template",
		"/system/message-template",
		"/system/report",
		"/system/data-audit",
		"/system/oauth-log",
		// 演示端点（平台级演示，非租户功能）
		"/system/crypto",
		"/system/repeat",
		// 流程定义设计器（平台级定义；运行时 my-todo/history/task 等个人端点不受影响）
		"/system/flow/definitions",
		"/system/flow/deploy",
		"/system/flow/design",
		"/system/flow/design-graph",
		"/system/flow/definition",
		// 反馈管理面（个人提交 /system/feedback/submit 不受影响）
		"/system/feedback/page",
		"/system/feedback/detail",
		"/system/feedback/status",
		"/system/feedback/remove"
	);

	/**
	 * 平台超管专属「写」路径：GET 只读放行（租户管理员授权 UI/字典运行时依赖菜单树等只读数据），
	 * 非 GET 写请求仅平台超管——sys_menu/sys_dict/sys_param 为平台全局表，租户不得改写。
	 */
	private static final List<String> PLATFORM_WRITE_PREFIXES = List.of(
		"/system/menu",
		"/system/dict",
		"/system/dict-biz",
		"/system/param",
		"/system/client"
	);

	private TenantPackageModules() {
	}

	/** 是否平台超管专属路径 */
	public static boolean isPlatformOnly(String path) {
		return matchesAny(path, PLATFORM_ONLY_PREFIXES);
	}

	/** 是否平台超管专属「写」路径（GET 只读放行，写操作仅平台超管；字典批量查询属全局共享只读助手，不算配置写） */
	public static boolean isPlatformWrite(String path) {
		if (path.startsWith("/system/dict/batch") || path.startsWith("/system/dict-biz/batch")) {
			return false;
		}
		return matchesAny(path, PLATFORM_WRITE_PREFIXES);
	}

	/**
	 * 该路径受套餐管控所需的授权路由 name 集合：
	 * {@code null} 表示不受管控（豁免的助手/个人/共享路径，或未注册的公共模块）→ 放行。
	 */
	public static Set<String> gatedRouteNames(String path) {
		if (isExempt(path)) {
			return null;
		}
		for (Map.Entry<String, Set<String>> e : GATED.entrySet()) {
			if (matchesPrefix(path, e.getKey())) {
				return e.getValue();
			}
		}
		return null;
	}

	private static boolean isExempt(String path) {
		String seg = path.substring(path.lastIndexOf('/') + 1);
		if (HELPER_SUFFIXES.contains(seg)) {
			return true;
		}
		return matchesAny(path, SHARED_PREFIXES);
	}

	private static boolean matchesAny(String path, List<String> prefixes) {
		for (String p : prefixes) {
			if (matchesPrefix(path, p)) {
				return true;
			}
		}
		return false;
	}

	/** 段感知前缀匹配：path == P 或 path 以 P + "/" 开头 */
	private static boolean matchesPrefix(String path, String prefix) {
		return path.equals(prefix) || path.startsWith(prefix + "/");
	}
}
