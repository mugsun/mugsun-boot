package com.mugsun.boot.system.file;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.mugsun.boot.common.constant.OssConstants;
import com.mugsun.boot.system.mapper.SysAttachMapper;
import com.mugsun.boot.tenant.TenantContext;
import com.mybatisflex.core.query.QueryWrapper;
import org.dromara.x.file.storage.core.FileInfo;
import org.dromara.x.file.storage.core.FileStorageService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * 存储对象待清理队列：直传回验失败、搬到新 key 后的旧 key，删不掉时先记进 Redis 列表，
 * 定时任务每 10 分钟重删一次；连续失败 {@value #MAX_ATTEMPTS} 次后打 error 日志并放弃，交给人工处理。
 * <p>队列项可带 {@code notBefore}（毫秒时间戳），没到时间不删也不计失败次数：用于直传暂存 key
 * 在预签名 URL 过期后再删一次，防止有效期内被再次 PUT 后遗留。
 * <p>本地存储不支持直传，但仍按天清掉各本地平台暂存前缀 {@code tmp/} 下超过 1 天的文件兜底。
 * <p><b>删除前护栏</b>：只删两类 key——暂存前缀 {@code tmp/} 下的，或本次登记流程刚复制出、标了
 * {@code fresh} 且文件名是服务端随机名的；两类都必须没有在 sys_attach 登记。已登记的对象一律不删，
 * 打告警并丢弃该队列项；查登记表出错时不删，原样放回且不计失败次数；连续查不了超过
 * {@value #MAX_UNKNOWN_ROUNDS} 轮（约 1 天）打一次 error 日志，仍然不删。
 * <p>集群下用 Redis SETNX 锁保证同一时刻只有一个节点在扫；释放锁用 Lua 先比较 token 再删。
 */
@Component
public class PendingObjectCleaner {

	private static final Logger log = LoggerFactory.getLogger(PendingObjectCleaner.class);
	public static final String QUEUE_KEY = "mugsun:oss:pending-delete";
	private static final String LOCK_KEY = "mugsun:oss:pending-delete:lock";
	static final int MAX_ATTEMPTS = 20;
	/** 查登记表连续失败的告警轮数：144 轮 × 10 分钟 ≈ 1 天 */
	static final int MAX_UNKNOWN_ROUNDS = 144;
	private static final int ALERT_SAMPLE = 5;
	private static final int BATCH = 200;

	private final StringRedisTemplate redis;
	private final FileStorageService fileStorageService;
	private final SysAttachMapper attachMapper;
	private final ObjectMapper om = new ObjectMapper();
	/** 服务端随机存储名：UploadPolicy.storedName 生成的 32 位十六进制 + 扩展名 */
	static final java.util.regex.Pattern RANDOM_NAME = java.util.regex.Pattern.compile("[0-9a-f]{32}\\.[a-z0-9]{1,8}");
	/** 先比较 token 再删，避免锁过期后误删别的节点刚拿到的锁 */
	static final org.springframework.data.redis.core.script.RedisScript<Long> UNLOCK =
		new org.springframework.data.redis.core.script.DefaultRedisScript<>(
			"if redis.call('get', KEYS[1]) == ARGV[1] then return redis.call('del', KEYS[1]) else return 0 end",
			Long.class);
	private static final long LOCAL_STAGING_TTL_MS = 24L * 3600 * 1000;

	public PendingObjectCleaner(StringRedisTemplate redis, FileStorageService fileStorageService,
								SysAttachMapper attachMapper) {
		this.redis = redis;
		this.fileStorageService = fileStorageService;
		this.attachMapper = attachMapper;
	}

	/** 暂存 key：立即删一次，失败就入队，不向调用方抛错 */
	public void deleteOrEnqueue(FileInfo info) {
		deleteOrEnqueue(info, false);
	}

	/** fresh=true 表示本次登记流程刚复制出、还没登记的随机 key */
	public void deleteOrEnqueue(FileInfo info, boolean fresh) {
		Verdict v = check(info, fresh);
		if (v == Verdict.REFUSE) {
			return;
		}
		if (v == Verdict.UNKNOWN || !tryDelete(info)) {
			enqueue(info, 0, 0L, fresh);
		}
	}

	/** 排一条延迟删除的 fresh key：到期后由 check 查登记表决定删不删 */
	public void enqueueFreshAfter(FileInfo info, long notBefore) {
		enqueue(info, 0, notBefore, true);
	}

	/** 排一条不早于 notBefore 才执行的暂存 key 删除 */
	public void enqueueAfter(FileInfo info, long notBefore) {
		enqueue(info, 0, notBefore, false);
	}

	void enqueue(FileInfo info, int attempts) {
		enqueue(info, attempts, 0L, false);
	}

	enum Verdict { ALLOW, REFUSE, UNKNOWN }

	/** 删除前护栏，见类注释 */
	Verdict check(FileInfo info, boolean fresh) {
		String path = info.getPath() == null ? "" : info.getPath();
		String name = info.getFilename() == null ? "" : info.getFilename();
		boolean staging = path.equals(OssConstants.PATH_STAGING) || path.startsWith(OssConstants.PATH_STAGING);
		boolean freshKey = fresh && RANDOM_NAME.matcher(name).matches()
			&& (path.equals(OssConstants.PATH_PUBLIC) || path.equals(OssConstants.PATH_PRIVATE));
		if (!staging && !freshKey) {
			log.warn("待删对象不在暂存前缀，也不是本次新生成的 key，拒绝删除并丢弃：{}/{}{}", info.getPlatform(), path, name);
			return Verdict.REFUSE;
		}
		long registered;
		try {
			registered = TenantContext.ignore(() -> attachMapper.selectCountByQuery(QueryWrapper.create()
				.eq("platform", info.getPlatform()).eq("path", path).eq("filename", name)));
		} catch (Exception e) {
			log.warn("查附件登记失败，暂不删除：{}{}", path, name);
			return Verdict.UNKNOWN;
		}
		if (registered > 0) {
			log.warn("待删对象已在附件表登记，拒绝删除并丢弃：{}/{}{}", info.getPlatform(), path, name);
			return Verdict.REFUSE;
		}
		return Verdict.ALLOW;
	}

	void enqueue(FileInfo info, int attempts, long notBefore, boolean fresh) {
		enqueue(info, attempts, notBefore, fresh, 0);
	}

	void enqueue(FileInfo info, int attempts, long notBefore, boolean fresh, int unknownRounds) {
		ObjectNode n = om.createObjectNode();
		n.put("platform", info.getPlatform());
		n.put("basePath", info.getBasePath() == null ? "" : info.getBasePath());
		n.put("path", info.getPath());
		n.put("filename", info.getFilename());
		n.put("attempts", attempts);
		if (fresh) {
			n.put("fresh", true);
		}
		if (notBefore > 0) {
			n.put("notBefore", notBefore);
		}
		if (unknownRounds > 0) {
			n.put("unknownRounds", unknownRounds);
		}
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
			sweepLocalStaging();
		} finally {
			redis.execute(UNLOCK, List.of(LOCK_KEY), token);
		}
	}

	/** 处理当前队列里的条目（每轮最多 BATCH 条），返回删除成功的条数 */
	int drain() {
		int ok = 0;
		int alerted = 0;
		List<String> alertSample = new ArrayList<>();
		Long size = redis.opsForList().size(QUEUE_KEY);
		long n = Math.min(size == null ? 0 : size, BATCH);
		for (long i = 0; i < n; i++) {
			String json = redis.opsForList().leftPop(QUEUE_KEY);
			if (json == null) {
				break;
			}
			FileInfo info;
			int attempts;
			long notBefore;
			boolean fresh;
			int unknownRounds;
			try {
				JsonNode j = om.readTree(json);
				info = new FileInfo().setPlatform(j.path("platform").asText()).setBasePath(j.path("basePath").asText(""))
					.setPath(j.path("path").asText()).setFilename(j.path("filename").asText());
				attempts = j.path("attempts").asInt();
				notBefore = j.path("notBefore").asLong(0L);
				fresh = j.path("fresh").asBoolean(false);
				unknownRounds = j.path("unknownRounds").asInt(0);
			} catch (Exception e) {
				log.error("待清理队列里有读不出的条目，已丢弃：{}", json);
				continue;
			}
			if (notBefore > System.currentTimeMillis()) {
				redis.opsForList().rightPush(QUEUE_KEY, json);
				continue;
			}
			Verdict v = check(info, fresh);
			if (v == Verdict.REFUSE) {
				continue;
			}
			if (v == Verdict.UNKNOWN) {
				// 查登记表失败不是删除失败：原样放回，不加失败次数；只记轮数，超限告警一次，仍不删
				int rounds = unknownRounds + 1;
				if (rounds == MAX_UNKNOWN_ROUNDS + 1) {
					alerted++;
					if (alertSample.size() < ALERT_SAMPLE) {
						alertSample.add(info.getPlatform() + "/" + info.getPath() + info.getFilename());
					}
				}
				enqueue(info, attempts, 0L, fresh, rounds);
				continue;
			}
			if (tryDelete(info)) {
				ok++;
			} else if (attempts + 1 >= MAX_ATTEMPTS) {
				log.error("存储对象重删 {} 次仍失败，已放弃，需人工删除：{}/{}{}", MAX_ATTEMPTS, info.getPlatform(),
					info.getPath(), info.getFilename());
			} else {
				enqueue(info, attempts + 1, 0L, fresh);
			}
		}
		if (alerted > 0) {
			// 一轮只汇总打一条，库长时间不可用时不会按条目刷屏
			log.error("{} 个待删对象已连续 {} 轮查不了附件登记表，仍保留在队列，请检查数据库；前几个：{}",
				alerted, MAX_UNKNOWN_ROUNDS, alertSample);
		}
		return ok;
	}

	/** 本地平台暂存前缀下超过 1 天的文件删掉（本地不签直传，正常不会有文件，这里只兜底） */
	int sweepLocalStaging() {
		int removed = 0;
		long cutoff = System.currentTimeMillis() - LOCAL_STAGING_TTL_MS;
		for (org.dromara.x.file.storage.core.platform.FileStorage fs : fileStorageService.getFileStorageList()) {
			if (!(fs instanceof org.dromara.x.file.storage.core.platform.LocalPlusFileStorage local)) {
				continue;
			}
			java.io.File dir = new java.io.File(local.getAbsolutePath(
				(local.getBasePath() == null ? "" : local.getBasePath()) + OssConstants.PATH_STAGING));
			java.io.File[] files = dir.listFiles();
			if (files == null) {
				continue;
			}
			for (java.io.File f : files) {
				if (f.isFile() && f.lastModified() < cutoff && f.delete()) {
					removed++;
				}
			}
		}
		return removed;
	}
}