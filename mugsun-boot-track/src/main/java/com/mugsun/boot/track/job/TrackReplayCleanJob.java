package com.mugsun.boot.track.job;

import com.mugsun.boot.common.constant.TrackConstants;
import com.mugsun.boot.track.TrackDS;
import com.mugsun.boot.track.TrackReplayStorage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;

/**
 * 回放保留期清理任务（G100）：按 {@code track_app.replay_retention_days} 逐应用到期线清理——
 * 删对象存储全部块 + 逻辑删元数据 + 复位 track_session.has_replay（防前端对已无数据的会话亮回放入口）。
 * <p><b>到期线</b>：start_time 早于 now − replay_retention_days；应用已删/缺省回退
 * {@value TrackConstants#REPLAY_DEFAULT_RETENTION_DAYS} 天（回放最高敏感，宁可早清不可滞留）。
 * <p><b>隐私优先</b>：个别对象删除失败只告警不阻塞元数据逻辑删（读取以元数据为门，逻辑删即不可见；
 * 残留对象留待下轮/人工，不因此回放可见期外泄）。
 * <p><b>节奏</b>：每小时 tick + 内存节流每日一轮（多节点各自节流 + 分布式锁兜底，同分区维护范式）；
 * 单轮内按 {@value TrackConstants#REPLAY_CLEAN_BATCH_SIZE} 行分批循环至清空（封顶 100 批防爆量）。
 */
@Component
@TrackDS
public class TrackReplayCleanJob {

	private static final Logger log = LoggerFactory.getLogger(TrackReplayCleanJob.class);

	/** 单轮分批上限（100 × 500 行 = 5 万/轮，正常量级远低于此；防异常积压下单轮失控） */
	private static final int MAX_BATCHES_PER_ROUND = 100;

	/** 到期回放查询：逐应用保留期（应用缺失回退默认）；storage_key 空（从未落储）的残行也一并捞出清理。
	 *  到期线取 Java 侧 UTC 墙钟绑定（与 TrackEventStore「TIMESTAMP=UTC 墙钟」写入规约同口径，不依赖库会话时区） */
	private static final String EXPIRED_QUERY = "SELECT r.session_id, r.storage_key, r.storage_platform,"
		+ " r.storage_base_path, r.last_seq FROM track_replay r"
		+ " LEFT JOIN track_app a ON a.app_key = r.app_key AND a.is_deleted = 0"
		+ " WHERE r.is_deleted = 0"
		+ " AND r.start_time < ? - make_interval(days => coalesce(a.replay_retention_days, ?))"
		+ " ORDER BY r.start_time LIMIT ?";

	/** 逻辑删元数据（读取以元数据为门，删即全站不可见） */
	private static final String MARK_DELETED = "UPDATE track_replay SET is_deleted = 1, update_time = now()"
		+ " WHERE session_id = ? AND is_deleted = 0";

	/** 复位会话回放标记（回放已清，会话不再亮回放入口） */
	private static final String RESET_SESSION_FLAG = "UPDATE track_session SET has_replay = 0, update_time = now()"
		+ " WHERE session_id = ? AND is_deleted = 0 AND has_replay = 1";

	private final JdbcTemplate jdbc;
	private final TrackJobGuard guard;
	private final TrackReplayStorage storage;
	/** 上次清理日期（UTC；内存节流每日一轮，多节点各自节流 + 分布式锁兜底） */
	private volatile LocalDate lastCleanDay;

	public TrackReplayCleanJob(DataSource dataSource, TrackJobGuard guard, TrackReplayStorage storage) {
		this.jdbc = new JdbcTemplate(dataSource);
		this.guard = guard;
		this.storage = storage;
	}

	/** 每小时 tick：每日一轮清理（到期判定按行级保留期，幂等） */
	@Scheduled(fixedDelay = TrackConstants.REPLAY_CLEAN_TICK_MS)
	public void tick() {
		LocalDate today = LocalDate.now(ZoneOffset.UTC);
		if (lastCleanDay != null && !today.isAfter(lastCleanDay)) {
			return;
		}
		cleanNow();
	}

	/** 供 PowerJob 处理器/集成测试手动触发：立即执行一轮（不经节流；带分布式锁），返回执行摘要 */
	public String cleanNow() {
		return guard.withLock(TrackConstants.LOCK_REPLAY_CLEAN, this::doClean, "未获调度锁（他节点执行中），跳过本轮");
	}

	/** 一轮清理（package-private 供测试直调）：分批循环至无到期行或触分批上限 */
	String doClean() {
		int cleaned = 0;
		int failedObjects = 0;
		int batches = 0;
		while (batches < MAX_BATCHES_PER_ROUND) {
			List<Map<String, Object>> expired = jdbc.queryForList(EXPIRED_QUERY,
				LocalDateTime.now(ZoneOffset.UTC),
				TrackConstants.REPLAY_DEFAULT_RETENTION_DAYS, TrackConstants.REPLAY_CLEAN_BATCH_SIZE);
			if (expired.isEmpty()) {
				break;
			}
			batches++;
			for (Map<String, Object> row : expired) {
				failedObjects += cleanOne(row);
				cleaned++;
			}
		}
		lastCleanDay = LocalDate.now(ZoneOffset.UTC);
		String summary = "回放保留期清理完成：清理 " + cleaned + " 个会话（对象删除失败 " + failedObjects + " 个）";
		if (cleaned > 0 || failedObjects > 0) {
			log.info(summary);
		}
		return summary;
	}

	/** 清一条：删对象（可空/失败不阻塞）→ 逻辑删元数据 → 复位会话标记；返回对象删除失败数 */
	private int cleanOne(Map<String, Object> row) {
		String sessionId = (String) row.get("session_id");
		String storageKey = (String) row.get("storage_key");
		int failed = 0;
		if (storageKey != null) {
			int lastSeq = row.get("last_seq") == null ? -1 : ((Number) row.get("last_seq")).intValue();
			failed = storage.deleteAll((String) row.get("storage_platform"),
				(String) row.get("storage_base_path"), storageKey, lastSeq);
			if (failed > 0) {
				log.warn("回放会话 {} 有 {} 个块删除失败（残留对象待下轮/人工；元数据照常逻辑删，隐私优先）", sessionId, failed);
			}
		}
		jdbc.update(MARK_DELETED, sessionId);
		jdbc.update(RESET_SESSION_FLAG, sessionId);
		return failed;
	}
}
