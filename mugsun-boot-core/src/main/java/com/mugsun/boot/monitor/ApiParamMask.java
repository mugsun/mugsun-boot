package com.mugsun.boot.monitor;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.mugsun.boot.common.constant.MonitorConstants;

import java.util.Iterator;
import java.util.Map;
import java.util.Set;

/**
 * 访问日志参数结构化递归脱敏：Jackson tree 任意深度敏感 key 值置 {@code ***}，再整体截断。
 * <p>与 {@code AuditMask} 边界：AuditMask 是 oper_log 序列化串的正则级脱敏（保持不动）；
 * 本类面向访问日志的原始 body/query 参数，先解析成树再递归脱敏，嵌套结构不泄漏。
 * 敏感 key 名单与 AuditMask 同源（密码/密钥/令牌/签名类）。
 */
public final class ApiParamMask {

	/** 敏感 key 名单（小写比较）：密码/密钥/令牌/凭据/签名类 + PII（手机号/身份证）+ 验证码类（与 AuditMask 同源） */
	private static final Set<String> SENSITIVE = Set.of(
		"password", "oldpassword", "newpassword", "dspassword", "pwd", "passwd",
		"secret", "clientsecret", "client_secret", "apikey", "accesstoken", "access_token",
		"refreshtoken", "refresh_token", "token",
		"credential", "credentials", "privatekey", "sign",
		"phone", "mobile", "idcard", "id_card", "code", "smscode", "captchacode", "captcha_code");

	private ApiParamMask() {
	}

	/** JSON 原文递归脱敏 + 截断；非 JSON 原文直接截断（降级不脱敏，query/form 走 {@link #maskParams}） */
	public static String maskJson(ObjectMapper om, String raw) {
		if (raw == null || raw.isBlank()) {
			return raw;
		}
		try {
			JsonNode tree = om.readTree(raw);
			maskNode(null, tree);
			return truncate(om.writeValueAsString(tree));
		} catch (Exception e) {
			return truncate(raw);
		}
	}

	/** query/form 参数表脱敏 + 截断：转 JSON 树后走同一递归逻辑 */
	public static String maskParams(ObjectMapper om, Map<String, String[]> params) {
		if (params == null || params.isEmpty()) {
			return null;
		}
		ObjectNode root = om.createObjectNode();
		params.forEach((k, vs) -> {
			if (vs == null || vs.length == 0) {
				root.put(k, "");
			} else if (vs.length == 1) {
				root.put(k, vs[0]);
			} else {
				ArrayNode arr = root.putArray(k);
				for (String v : vs) {
					arr.add(v);
				}
			}
		});
		maskNode(null, root);
		try {
			return truncate(om.writeValueAsString(root));
		} catch (Exception e) {
			return null;
		}
	}

	/** 递归：命中敏感 key 的字段值整体替换为 ***（不再下钻），否则逐层下钻对象/数组 */
	private static void maskNode(String key, JsonNode node) {
		if (node == null) {
			return;
		}
		if (node.isObject()) {
			ObjectNode obj = (ObjectNode) node;
			Iterator<Map.Entry<String, JsonNode>> fields = obj.fields();
			while (fields.hasNext()) {
				Map.Entry<String, JsonNode> field = fields.next();
				if (SENSITIVE.contains(field.getKey().toLowerCase())) {
					obj.put(field.getKey(), MonitorConstants.MASK);
				} else {
					maskNode(field.getKey(), field.getValue());
				}
			}
			return;
		}
		if (node.isArray()) {
			for (JsonNode child : node) {
				maskNode(key, child);
			}
		}
	}

	private static String truncate(String s) {
		return s.length() > MonitorConstants.PARAMS_MAX_LEN
			? s.substring(0, MonitorConstants.PARAMS_MAX_LEN) + "...(truncated)" : s;
	}
}
