package com.mugsun.boot.ai.flow;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.Collections;
import java.util.Map;

/**
 * 从 flow_node.ext 解析 AI 节点配置。
 */
public record AiApprovalNodeConfig(
	Long aiFlowId,
	Map<String, String> inputMap,
	Map<String, String> outputMap,
	long timeoutMs,
	String onFailure
) {
	public static AiApprovalNodeConfig parse(ObjectMapper om, String ext) throws Exception {
		if (ext == null || ext.isBlank()) {
			throw new IllegalArgumentException("missing ext");
		}
		Map<String, Object> m = om.readValue(ext, new TypeReference<>() {
		});
		Object idObj = m.get("aiFlowId");
		Long id = null;
		if (idObj instanceof Number n) {
			id = n.longValue();
		} else if (idObj != null) {
			String s = String.valueOf(idObj).trim();
			if (!s.isEmpty() && !"null".equalsIgnoreCase(s)) {
				id = Long.valueOf(s);
			}
		}
		@SuppressWarnings("unchecked")
		Map<String, String> in = m.get("inputMap") instanceof Map<?, ?> mm
			? castStrMap(mm) : Collections.emptyMap();
		@SuppressWarnings("unchecked")
		Map<String, String> out = m.get("outputMap") instanceof Map<?, ?> mm
			? castStrMap(mm) : Collections.emptyMap();
		long timeout = AiApprovalConstants.DEFAULT_TIMEOUT_MS;
		Object t = m.get("timeoutMs");
		if (t instanceof Number n && n.longValue() > 0) {
			timeout = n.longValue();
		} else if (t != null) {
			try {
				long v = Long.parseLong(String.valueOf(t).trim());
				if (v > 0) {
					timeout = v;
				}
			} catch (NumberFormatException ignored) {
				// keep default
			}
		}
		timeout = AiApprovalConstants.clampTimeoutMs(timeout);
		String onFailure = m.get("onFailure") == null ? AiApprovalConstants.ON_FAILURE_REJECT_LAST
			: String.valueOf(m.get("onFailure")).trim();
		if (onFailure.isEmpty()) {
			onFailure = AiApprovalConstants.ON_FAILURE_REJECT_LAST;
		}
		return new AiApprovalNodeConfig(id, in, out, timeout, onFailure);
	}

	private static Map<String, String> castStrMap(Map<?, ?> raw) {
		Map<String, String> out = new java.util.LinkedHashMap<>();
		raw.forEach((k, v) -> {
			if (k != null && v != null) {
				out.put(String.valueOf(k), String.valueOf(v));
			}
		});
		return out;
	}
}
