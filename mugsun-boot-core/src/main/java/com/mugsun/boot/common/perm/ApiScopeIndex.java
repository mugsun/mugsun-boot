package com.mugsun.boot.common.perm;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 已启用的接口权限快照。请求期只读内存，不在拦截时查库。
 */
public final class ApiScopeIndex {

	public record Rule(long id, String name, String path) {
	}

	private static volatile List<Rule> rules = List.of();
	/** 规则 id → 已授权的「租户编号 + 角色编码」 */
	private static volatile Map<Long, Set<String>> grants = Map.of();

	private ApiScopeIndex() {
	}

	public static synchronized void replace(List<Rule> enabled, Map<Long, Set<String>> granted) {
		rules = enabled == null ? List.of() : List.copyOf(enabled);
		grants = granted == null ? Map.of() : Map.copyOf(granted);
	}

	public static String grantKey(String tenantId, String roleCode) {
		return (tenantId == null ? "" : tenantId) + "\u001f" + (roleCode == null ? "" : roleCode);
	}

	public static List<Rule> snapshot() {
		return rules;
	}

	/** 命中最长路径。路径本身或更长的子路径都算命中。 */
	public static Rule match(String uri) {
		if (uri == null || uri.isBlank()) {
			return null;
		}
		String path = uri;
		int query = path.indexOf('?');
		if (query >= 0) {
			path = path.substring(0, query);
		}
		if (path.length() > 1 && path.endsWith("/")) {
			path = path.substring(0, path.length() - 1);
		}
		Rule best = null;
		for (Rule rule : rules) {
			if (!covers(rule.path(), path)) {
				continue;
			}
			if (best == null || rule.path().length() > best.path().length()) {
				best = rule;
			}
		}
		return best;
	}

	public static boolean allowed(long ruleId, String tenantId, Collection<String> roleCodes) {
		if (roleCodes == null || roleCodes.isEmpty()) {
			return false;
		}
		Set<String> granted = grants.get(ruleId);
		if (granted == null || granted.isEmpty()) {
			return false;
		}
		for (String code : roleCodes) {
			if (code != null && granted.contains(grantKey(tenantId, code))) {
				return true;
			}
		}
		return false;
	}

	public static int grantCount(long ruleId) {
		Set<String> granted = grants.get(ruleId);
		return granted == null ? 0 : granted.size();
	}

	private static boolean covers(String rulePath, String requestPath) {
		return requestPath.equals(rulePath) || requestPath.startsWith(rulePath + "/");
	}
}
