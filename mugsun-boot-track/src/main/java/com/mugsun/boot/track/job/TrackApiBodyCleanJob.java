package com.mugsun.boot.track.job;

import com.mugsun.boot.common.constant.TrackConstants;
import com.mugsun.boot.track.TrackApiBodyStorage;
import com.mugsun.boot.track.TrackDS;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 接口响应体保留期清理任务（G102）：按 {@code track_app.api_body_retention_days} 逐应用到期线删对象。
 * <p><b>清单来源</b>（本域无元数据表）：track_event 中 props->>'body_ref' 非空的事件行——
 * 对象键 = api-body/{app_key}/{yyyyMM}/{event_id}.json.gz（yyyyMM 取事件 received_at 月）纯推导，逐个删对象。
 * 事件行本身不动（props.body_ref 留存：body 过期后读取按「body 未采集或已清理」诚实兜底，时间线仍可下钻事件）。
 * <p><b>到期线</b>：received_at 早于 now − api_body_retention_days；应用已删/缺省回退
 * {@value TrackConstants#API_BODY_DEFAULT_RETENTION_DAYS} 天（响应体高敏感，宁可早清不可滞留）。
 * 配套约束：body 保留上限 {@value TrackConstants#API_BODY_MAX_RETENTION_DAYS} 天 远短于事件明细默认 90 天，
 * 清单（事件行）必然后于对象消失——若事件保留期被误配短于 body 保留期，残留对象随清单消失孤儿化（小体量，可接受）。
 * <p><b>隐私优先</b>：个别对象删除失败只告警不阻塞（下轮重试；事件行不动，清单天然幂等可重入）。
 * <p><b>节奏</b>：每小时 tick + 内存节流每日一轮（多节点各自节流 + 分布式锁兜底，同回放清理范式）；
 * 单轮内按 {@value TrackConstants#API_BODY_CLEAN_BATCH_SIZE} 行分批循环至清空（封顶 100 批防爆量）。
 */
@Component
@TrackDS
public class TrackApiBodyCleanJob {

	private static final Logger log = LoggerFactory.getLogger(TrackApiBodyCleanJob.class);

	/** 单轮分批上限（100 × 500 行 = 5 万/轮，正常量级远低于此；防异常积压下单轮失控） */
	private static final int MAX_BATCHES_PER_ROUND = 100;

	/** 到期响应体清单查询：逐应用保留期（应用缺失回退默认）；对象键按事件 received_at + body_ref 纯推导。
	 *  到期线取 Java 侧 UTC 墙钟绑定（与 TrackEventStore「TIMESTAMP=UTC 墙钟」写入规约同口径，不依赖库会话时区） */
	private static final String EXPIRED_QUERY = "SELECT e.app_key, e.received_at,"
		+ " e.props->>'" + TrackConstants.PROP_BODY_REF + "' AS ref"
		+ " FROM track_event e"
		+ " LEFT JOIN track_app a ON a.app_key = e.app_key AND a.is_deleted = 0"
		+ " WHERE e.props->>'" + TrackConstants.PROP_BODY_REF + "' IS NOT NULL"
		+ " AND e.received_at < ? - make_interval(days => coalesce(a.api_body_retention_days, ?))"
		+ " ORDER BY e.received_at LIMIT ?";

	private final JdbcTemplate jdbc;
	private final TrackJobGuard guard;
	private final TrackApiBodyStorage storage;
	/** 上次清理日期（UTC；内存节流每日一轮，多节点各自节流 + 分布式锁兜底） */
	private volatile LocalDate lastCleanDay;

	public TrackApiBodyCleanJob(DataSource dataSource, TrackJobGuard guard, TrackApiBodyStorage storage) {
		this.jdbc = new JdbcTemplate(dataSource);
		this.guard = guard;
		this.storage = storage;
	}

	/** 每小时 tick：每日一轮清理（到期判定按行级保留期，幂等） */
	@Scheduled(fixedDelay = TrackConstants.API_BODY_CLEAN_TICK_MS)
	public void tick() {
		LocalDate today = LocalDate.now(ZoneOffset.UTC);
		if (lastCleanDay != null && !today.isAfter(lastCleanDay)) {
			return;
		}
		cleanNow();
	}

	/** 供 PowerJob 处理器/集成测试手动触发：立即执行一轮（不经节流；带分布式锁），返回执行摘要 */
	public String cleanNow() {
		return guard.withLock(TrackConstants.LOCK_API_BODY_CLEAN, this::doClean, "未获调度锁（他节点执行中），跳过本轮");
	}

	/** 一轮清理（package-private 供测试直调）：分批循环至无到期行或触分批上限。
	 *  失败行本轮去重跳过（processed 集）：对象删除失败不抹清单（事件行不动，天然幂等），
	 *  若无去重同一失败行会被反复重试至分批上限——每行每轮只试一次，留待明日下轮/人工 */
	String doClean() {
		int cleaned = 0;
		int failedObjects = 0;
		int batches = 0;
		Set<String> processed = new HashSet<>();
		while (batches < MAX_BATCHES_PER_ROUND) {
			List<Map<String, Object>> expired = jdbc.queryForList(EXPIRED_QUERY,
				OffsetDateTime.now(ZoneOffset.UTC),
				TrackConstants.API_BODY_DEFAULT_RETENTION_DAYS, TrackConstants.API_BODY_CLEAN_BATCH_SIZE);
			if (expired.isEmpty()) {
				break;
			}
			batches++;
			int fresh = 0;
			for (Map<String, Object> row : expired) {
				String rowKey = row.get("app_key") + "/" + row.get("ref");
				if (!processed.add(rowKey)) {
					continue;
				}
				fresh++;
				failedObjects += cleanOne(row);
				cleaned++;
			}
			if (fresh == 0) {
				break;
			}
		}
		lastCleanDay = LocalDate.now(ZoneOffset.UTC);
		String summary = "接口响应体保留期清理完成：清理 " + cleaned + " 个对象（删除失败 " + failedObjects + " 个）";
		if (cleaned > 0 || failedObjects > 0) {
			log.info(summary);
		}
		return summary;
	}

	/** 清一条：按事件行推导对象键删除；返回删除失败数（失败留待下轮/人工，清单不动天然重试） */
	private int cleanOne(Map<String, Object> row) {
		String appKey = (String) row.get("app_key");
		String ref = (String) row.get("ref");
		if (ref == null) {
			return 0;
		}
		long receivedAtMs = toEpochMs(row.get("received_at"));
		try {
			if (!storage.delete(appKey, receivedAtMs, ref)) {
				log.warn("响应体对象删除返回 false：{}", TrackApiBodyStorage.relativeKey(appKey, receivedAtMs, ref));
				return 1;
			}
		} catch (Exception e) {
			log.warn("响应体对象删除异常 {}：{}", TrackApiBodyStorage.relativeKey(appKey, receivedAtMs, ref), e.getMessage());
			return 1;
		}
		return 0;
	}

	/** TIMESTAMPTZ 列 → epoch 毫秒（驱动按配置可能给 Timestamp 或 OffsetDateTime，统一收口） */
	private long toEpochMs(Object value) {
		if (value instanceof Timestamp ts) {
			return ts.toInstant().toEpochMilli();
		}
		if (value instanceof OffsetDateTime odt) {
			return odt.toInstant().toEpochMilli();
		}
		throw new IllegalStateException("事件 received_at 字段类型异常: " + value);
	}
}
