package com.mugsun.boot.common.perm;

import java.util.List;
import java.util.Map;

/**
 * 受控接口权限派生与豁免规则：供 {@link PermissionGuardInterceptor} 对 /system/** 写接口做「有码才放行」兜底。
 * <p>派生码 = {@code sys:<域>:<动作>}（域=/system/ 后第一段，动作按末段动词映射）。平台管理员持通配 {@code *} 恒通过，
 * 故本兜底只对无对应权限码的非管理员生效；个人域/共享/演示写接口在此豁免，不受管控。
 */
public final class PermissionModules {

	/** 写动作段 → 权限码动作 的映射（未列出的写动词取其自身） */
	private static final Map<String, String> ACTION = Map.ofEntries(
		Map.entry("submit", "save"), Map.entry("save", "save"),
		Map.entry("create", "save"), Map.entry("update", "save"), Map.entry("add", "save"),
		Map.entry("remove", "remove"), Map.entry("delete", "remove"),
		Map.entry("import", "import"),
		Map.entry("status", "edit"), Map.entry("enable", "edit"), Map.entry("disable", "edit"),
		Map.entry("grant", "grant")
	);

	/** 个人域 / 共享 / 演示写接口前缀：不要求管理员权限码（登录用户自有数据或平台公共/演示能力） */
	private static final List<String> EXEMPT_PREFIXES = List.of(
		"/system/workbench",
		// 内联自守卫端点（按 id 分 add/edit 走 StpUtil.checkPermission，非注解），交由其自身权限校验
		"/system/user/submit",
		"/system/notice/my", "/system/notice/read",
		"/system/message",
		"/system/file/upload",
		"/system/table-column",
		"/system/dict/batch",
		"/system/feedback/submit",
		"/system/customer",
		"/system/form/submit-data", "/system/form/data",
		"/system/help/view",
		"/system/repeat",
		"/system/crypto",
		// 工作流任务办理：鉴权由 warm-flow 办理人（permissionFlag）校验，被指派人方可办理，不套 RBAC 码
		"/system/flow/task",
		// 工作流发起：提交审批是登录用户的业务动作（流程定义控制审批人），不套 RBAC 码；流程定义管理(deploy/design)仍受管控
		"/system/flow/start",
		"/system/flow/start-by"
	);

	private PermissionModules() {
	}

	/** 是否豁免（个人/共享/演示写接口，不施加管控） */
	public static boolean isExempt(String path) {
		for (String p : EXEMPT_PREFIXES) {
			if (path.equals(p) || path.startsWith(p + "/")) {
				return true;
			}
		}
		return false;
	}

	/**
	 * 由请求路径派生受控写接口所需权限码；{@code null} 表示无法派生（非受控形态，放行）。
	 * 路径形如 {@code /system/<域>/<动作>[/...]}，域取第 2 段、动作取第 3 段。
	 */
	public static String deriveCode(String path) {
		String[] parts = path.split("/");
		if (parts.length < 4 || !"system".equals(parts[1])) {
			return null;
		}
		String domain = parts[2];
		String action = parts[3];
		if (domain.isBlank() || action.isBlank()) {
			return null;
		}
		return "sys:" + domain + ":" + ACTION.getOrDefault(action, action);
	}
}
