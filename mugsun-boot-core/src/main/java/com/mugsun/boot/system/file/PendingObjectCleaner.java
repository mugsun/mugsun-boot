package com.mugsun.boot.system.file;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.dromara.x.file.storage.core.FileInfo;
import org.dromara.x.file.storage.core.FileStorageService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.UUID;

/**
 * 存储对象待清理队列：直传回验失败、搬到新 key 后的旧 key，删不掉时先记进 Redis 列表，
 * 定时任务每 10 分钟重删一次；连续失败 {@value #MAX_ATTEMPTS} 次后打 error 日志并放弃，交给人工处理。
 * <p>集群下用 Redis SETNX 锁保证同一时刻只有一个节点在扫（同 NotifyRetryJob）。
 */
@Component
public class PendingObjectCleaner {

	private static final Logger log = LoggerFactory.getLogger(PendingObjectCleaner.class);
	public static final String QUEUE_KEY = "mugsun:oss:pending-delete";
	private static final String LOCK_KEY = "mugsun:oss:pending-delete:lock";
	static final int MAX_ATTEMPTS = 20;
	private static final int BATCH = 200;

	private final StringRedisTemplate redis;
	private final FileStorageService fileStorageService;
	private final ObjectMapper om = new ObjectMapper();

	public PendingObjectCleaner(StringRedisTemplate redis, FileStorageService fileStorageService) {
		this.redis = redis;
		this.fileStorageService = fileStorageService;
	}

	/** 立即删一次；失败就入队，不向调用方抛错 */
	public void deleteOrEnqueue(FileInfo info) {
		if (!tryDelete(info)) {
			enqueue(info, 0);
		}
	}

	void enqueue(FileInfo info, int attempts) {
		ObjectNode n = om.createObjectNode();
		n.put("platform", info.getPlatform());
		n.put("basePath", info.getBasePath() == null ? "" : info.getBasePath());
		n.put("path", info.getPath());
		n.put("filename", info.getFilename());
		n.put("attempts", attempts);
		try {
			redis.opsForList().rightPush(QUEUE_KEY, n.toString());
		} catch (Exception e) {
			log.error("存储对象删除失败且无法入待清理队列，需人工删除：{}/{}{}", info.getPlatform(), info.getPath(), info.getFilename());
		}
	}

	private boolean tryDelete(FileInfo info) {
		try {
			fileStorageService.getFileStorageVerify(info).delete(info);
			return true;
		} catch (Exception e) {
			log.warn("删除存储对象失败，稍后重试：{}{}", info.getPath(), info.getFilename());
			return false;
		}
	}

	@Scheduled(fixedDelay = 600_000L, initialDelay = 60_000L)
	public void sweep() {
		String token = UUID.randomUUID().toString();
		Boolean locked;
		try {
			locked = redis.opsForValue().setIfAbsent(LOCK_KEY, token, Duration.ofMinutes(5));
		} catch (Exception e) {
			return;
		}
		if (!Boolean.TRUE.equals(locked)) {
			return;
		}
		try {
			drain();
		} finally {
			if (token.equals(redis.opsForValue().get(LOCK_KEY))) {
				redis.delete(LOCK_KEY);
			}
		}
	}

	/** 处理当前队列里的条目（每轮最多 BATCH 条），返回删除成功的条数 */
	int drain() {
		int ok = 0;
		Long size = redis.opsForList().size(QUEUE_KEY);
		long n = Math.min(size == null ? 0 : size, BATCH);
		for (long i = 0; i < n; i++) {
			String json = redis.opsForList().leftPop(QUEUE_KEY);
			if (json == null) {
				break;
			}
			FileInfo info;
			int attempts;
			try {
				JsonNode j = om.readTree(json);
				info = new FileInfo().setPlatform(j.path("platform").asText()).setBasePath(j.path("basePath").asText(""))
					.setPath(j.path("path").asText()).setFilename(j.path("filename").asText());
				attempts = j.path("attempts").asInt();
			} catch (Exception e) {
				log.error("待清理队列里有读不出的条目，已丢弃：{}", json);
				continue;
			}
			if (tryDelete(info)) {
				ok++;
			} else if (attempts + 1 >= MAX_ATTEMPTS) {
				log.error("存储对象重删 {} 次仍失败，已放弃，需人工删除：{}/{}{}", MAX_ATTEMPTS, info.getPlatform(),
					info.getPath(), info.getFilename());
			} else {
				enqueue(info, attempts + 1);
			}
		}
		return ok;
	}
}
