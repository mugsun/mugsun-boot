package com.mugsun.boot.ai.flow;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;

/**
 * 扫描 AiApp DSL，收集节点 type；命中硬禁列表则拒绝内嵌执行。
 */
public final class AiCapabilityGuard {

	private AiCapabilityGuard() {
	}

	public static Set<String> collectNodeTypes(ObjectMapper om, String dsl) throws Exception {
		Set<String> types = new LinkedHashSet<>();
		JsonNode root = om.readTree(dsl == null || dsl.isBlank() ? "{}" : dsl);
		JsonNode nodes = root.path("nodes");
		if (!nodes.isArray()) {
			return types;
		}
		for (JsonNode n : nodes) {
			String t = n.path("type").asText("");
			if (t.isBlank() || "default".equals(t) || "custom".equals(t)) {
				t = n.path("data").path("type").asText("");
			}
			if (!t.isBlank()) {
				types.add(t.toLowerCase(Locale.ROOT).trim());
			}
		}
		return types;
	}

	/** @return 命中的禁类型（空=放行） */
	public static Set<String> deniedTypes(ObjectMapper om, String dsl) throws Exception {
		Set<String> hit = new LinkedHashSet<>();
		for (String t : collectNodeTypes(om, dsl)) {
			if (AiApprovalConstants.FORBIDDEN_NODE_TYPES.contains(t)) {
				hit.add(t);
			}
		}
		return hit;
	}
}
