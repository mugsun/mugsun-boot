package com.mugsun.boot.ai.support;

import com.mugsun.core.tool.exception.ServiceException;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 删除接口同时接受查询参数 {@code ids} 和 JSON {@code {id}} / {@code {ids}}。
 */
public final class AiIds {

	private AiIds() {
	}

	public static List<Long> parse(String ids, Map<String, Object> body) {
		String raw = ids;
		if ((raw == null || raw.isBlank()) && body != null) {
			Object value = body.get("ids") != null ? body.get("ids") : body.get("id");
			raw = value == null ? null : String.valueOf(value);
		}
		if (raw == null || raw.isBlank() || "null".equals(raw)) {
			throw new ServiceException("缺少记录 id");
		}
		List<Long> list = Arrays.stream(raw.split(","))
			.map(String::trim)
			.filter(part -> !part.isBlank() && !"null".equals(part))
			.map(Long::valueOf)
			.collect(Collectors.toList());
		if (list.isEmpty()) {
			throw new ServiceException("缺少记录 id");
		}
		return list;
	}

	public static Long one(Long id, Map<String, Object> body) {
		if (id != null) {
			return id;
		}
		if (body != null && body.get("id") != null) {
			return Long.valueOf(String.valueOf(body.get("id")));
		}
		throw new ServiceException("缺少记录 id");
	}
}
