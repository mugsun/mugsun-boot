package com.mugsun.boot.ai.flow;

import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * AI×审批流契约常量：已发布判定、能力面硬禁、失败码、默认超时/失败策略。
 * <p>安全收紧：强制 inputMap（空=无输入）、outputMap 目标键白名单、timeout 硬顶、notice 与 http 同禁。
 */
public final class AiApprovalConstants {

	private AiApprovalConstants() {
	}

	public static final long DEFAULT_TIMEOUT_MS = 120_000L;
	/** 内嵌执行硬顶（含设计器写入与运行期 clamp） */
	public static final long MAX_TIMEOUT_MS = 300_000L;
	public static final String ON_FAILURE_REJECT_LAST = "rejectLast";
	public static final String ON_FAILURE_STAY = "stay";
	public static final String ON_FAILURE_TRANSFER_ROLE_PREFIX = "transfer:role:";

	/** 允许被审批节点绑定的 AiApp.appType */
	public static final Set<String> ALLOWED_APP_TYPES = Set.of("workflow", "chatflow");

	/**
	 * 内嵌硬禁 DSL 节点 type（小写）及同义：命中即 AI_CAPABILITY_DENIED。
	 * 与 {@link AiFlowExecutor} switch 对齐；notice=出站渠道/Webhook，与 http 同级禁。
	 */
	public static final Set<String> FORBIDDEN_NODE_TYPES = Set.of(
		"db", "sql", "jdbc",
		"http", "https", "rest", "fetch", "webhook",
		"mcp", "tool",
		"code", "js", "javascript", "groovy", "script",
		"notice", "notify", "notification", "channel", "webhook_notice"
	);

	/** outputMap 目标键：约定四键 + ai_ext_ 前缀扩展（防写权限/角色等流程变量） */
	public static final Set<String> OUTPUT_ALLOWED_KEYS = Set.of(
		"ai_summary", "ai_pass", "ai_reason", "ai_run_id"
	);
	public static final String OUTPUT_EXT_PREFIX = "ai_ext_";

	/** inputSummary 脱敏键名（小写比对） */
	private static final Pattern SENSITIVE_KEY = Pattern.compile(
		".*(password|passwd|pwd|secret|token|authorization|auth|credential|api[_-]?key|access[_-]?key|private[_-]?key|session).*",
		Pattern.CASE_INSENSITIVE);

	public static final String ERR_APP_MISSING = "AI_APP_MISSING";
	public static final String ERR_APP_DISABLED = "AI_APP_DISABLED";
	public static final String ERR_APP_TYPE_DENIED = "AI_APP_TYPE_DENIED";
	public static final String ERR_TENANT_DENIED = "AI_TENANT_DENIED";
	public static final String ERR_CAPABILITY_DENIED = "AI_CAPABILITY_DENIED";
	public static final String ERR_TIMEOUT = "AI_TIMEOUT";
	public static final String ERR_EXECUTE = "AI_EXECUTE_ERROR";

	public static boolean isAllowedOutputKey(String key) {
		if (key == null || key.isBlank()) {
			return false;
		}
		String k = key.trim();
		if (OUTPUT_ALLOWED_KEYS.contains(k)) {
			return true;
		}
		return k.startsWith(OUTPUT_EXT_PREFIX) && k.length() > OUTPUT_EXT_PREFIX.length()
			&& k.matches("ai_ext_[A-Za-z0-9_]+");
	}

	public static boolean isSensitiveInputKey(String key) {
		if (key == null || key.isBlank()) {
			return false;
		}
		return SENSITIVE_KEY.matcher(key.trim().toLowerCase(Locale.ROOT)).matches();
	}

	public static long clampTimeoutMs(long timeoutMs) {
		if (timeoutMs <= 0) {
			return DEFAULT_TIMEOUT_MS;
		}
		return Math.min(timeoutMs, MAX_TIMEOUT_MS);
	}
}
