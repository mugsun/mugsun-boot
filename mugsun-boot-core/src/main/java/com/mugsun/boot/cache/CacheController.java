package com.mugsun.boot.cache;

import cn.dev33.satoken.annotation.SaCheckPermission;
import com.mugsun.core.tool.api.R;
import org.springframework.data.redis.connection.DataType;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * 缓存管理：按前缀分组查看 Redis 键、查看值/TTL、清除键（管理员）。
 */
@RestController
@RequestMapping("/system/cache")
@SaCheckPermission("sys:cache:manage")
public class CacheController {

	private final StringRedisTemplate redisTemplate;

	public CacheController(StringRedisTemplate redisTemplate) {
		this.redisTemplate = redisTemplate;
	}

	/** 缓存分组：按 key 前两段前缀聚合（如 mugsun:serial） + 键数 */
	@GetMapping("/groups")
	public R<List<Map<String, Object>>> groups() {
		Map<String, Integer> counts = new TreeMap<>();
		for (String k : scan("mugsun:*")) {
			String[] parts = k.split(":");
			String group = parts.length >= 2 ? parts[0] + ":" + parts[1] : k;
			counts.merge(group, 1, Integer::sum);
		}
		List<Map<String, Object>> list = new ArrayList<>();
		counts.forEach((name, count) -> {
			Map<String, Object> item = new LinkedHashMap<>();
			item.put("name", name);
			item.put("count", count);
			list.add(item);
		});
		return R.data(list);
	}

	/** 某分组下的键列表 */
	@GetMapping("/keys")
	public R<List<String>> keys(@RequestParam String group) {
		return R.data(scan(group + ":*"));
	}

	/** 键的值 + 类型 + 剩余 TTL（秒） */
	@GetMapping("/value")
	public R<Map<String, Object>> value(@RequestParam String key) {
		Map<String, Object> info = new LinkedHashMap<>();
		DataType type = redisTemplate.type(key);
		String typeCode = type == null ? "none" : type.code();
		info.put("key", key);
		info.put("type", typeCode);
		info.put("ttl", redisTemplate.getExpire(key));
		if (type == DataType.STRING) {
			info.put("value", redisTemplate.opsForValue().get(key));
		} else {
			info.put("value", "(" + typeCode + " 类型，暂不支持文本展示)");
		}
		return R.data(info);
	}

	/** 清除键（批量） */
	@PostMapping("/remove")
	public R<Void> remove(@RequestBody List<String> keys) {
		if (keys != null && !keys.isEmpty()) {
			redisTemplate.delete(keys);
		}
		return R.success("已清除");
	}

	private List<String> scan(String pattern) {
		return RedisScanSupport.scan(redisTemplate, pattern);
	}
}
