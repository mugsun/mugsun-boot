package com.mugsun.boot.job;

import org.springframework.data.redis.core.Cursor;
import org.springframework.data.redis.core.ScanOptions;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import tech.powerjob.worker.core.processor.ProcessResult;
import tech.powerjob.worker.core.processor.TaskContext;
import tech.powerjob.worker.core.processor.sdk.BasicProcessor;

import java.util.ArrayList;
import java.util.List;

/**
 * 缓存分组清理处理器：jobParams 传缓存分组前缀（如 mugsun:dict），SCAN 遍历后批量删除。
 * 安全边界：仅允许 mugsun: 命名空间、单次上限 1000 键（防爆量/防误清全域）。
 */
@Component
public class CacheEvictProcessor implements BasicProcessor {

	/** 允许清理的键命名空间（平台键统一 mugsun: 前缀） */
	private static final String NAMESPACE = "mugsun:";
	/** 单次清理键数上限（防爆量） */
	private static final int MAX_KEYS = 1000;

	private final StringRedisTemplate redisTemplate;

	public CacheEvictProcessor(StringRedisTemplate redisTemplate) {
		this.redisTemplate = redisTemplate;
	}

	@Override
	public ProcessResult process(TaskContext context) {
		String group = context.getJobParams() == null ? "" : context.getJobParams().trim();
		if (!group.startsWith(NAMESPACE) || group.contains("*") || group.length() <= NAMESPACE.length()) {
			return new ProcessResult(false, "jobParams 须为 " + NAMESPACE + " 开头的具体分组前缀（如 mugsun:dict），"
				+ "当前值：" + (group.isEmpty() ? "(空)" : group));
		}
		List<String> keys = new ArrayList<>();
		try (Cursor<String> cursor = redisTemplate.scan(
			ScanOptions.scanOptions().match(group + ":*").count(200).build())) {
			cursor.forEachRemaining(k -> {
				if (keys.size() < MAX_KEYS) {
					keys.add(k);
				}
			});
		}
		if (keys.size() >= MAX_KEYS) {
			return new ProcessResult(false, "命中键数超过单次上限 " + MAX_KEYS + "，已中止（请缩小分组范围）");
		}
		if (!keys.isEmpty()) {
			redisTemplate.delete(keys);
		}
		String summary = "缓存分组 " + group + " 已清理 " + keys.size() + " 个键";
		context.getOmsLogger().info(summary);
		return new ProcessResult(true, summary);
	}
}
