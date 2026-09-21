package com.mugsun.boot.monitor;

import cn.hutool.core.util.IdUtil;
import com.mugsun.boot.common.constant.MonitorConstants;
import com.mugsun.boot.system.service.ParamService;
import com.mybatisflex.core.row.Db;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.LocalDateTime;

/**
 * 日志保留期清理调度（G88 先例：固定 tick + 内存节流 + Redis 分布式锁）：
 * 定期物理清理 sys_api_log / sys_error_log / sys_oper_log 超 {@code monitor.log.retention-days}（默认 30 天）数据。
 * <p>oper_log 是逻辑删除表，此处为保留期物理清除；Db 原生 DELETE 不经租户插件，天然跨租户全量清理。
 */
@Component
public class LogCleanJob {

	private static final Logger log = LoggerFactory.getLogger(LogCleanJob.class);

	private final ParamService paramService;
	private final StringRedisTemplate redis;

	/** 上次清理时间戳（本节点内存节流；集群多节点各自节流 + 分布式锁兜底） */
	private volatile long lastCleanAt;

	public LogCleanJob(ParamService paramService, StringRedisTemplate redis) {
		this.paramService = paramService;
		this.redis = redis;
	}

	/** 固定 tick：到节流间隔才清理（保留期走 sys_param 热生效） */
	@Scheduled(fixedDelay = MonitorConstants.CLEAN_TICK_MS)
	public void tick() {
		long now = System.currentTimeMillis();
		if (now - lastCleanAt < MonitorConstants.CLEAN_INTERVAL_MS) {
			return;
		}
		lastCleanAt = now;
		cleanExpired();
	}

	/** 供 PowerJob 处理器手动触发：立即执行一次保留期清理，返回各表清理条数（不经节流） */
	public java.util.Map<String, Integer> cleanNow() {
		return doClean();
	}

	/** 物理清理超保留期日志（package-private 供集成测试直接触发，避免长时间 sleep 等待调度）。
	 *  api/error 与 oper 保留期分离：操作审计日志属等保留证，默认 180 天（monitor.oper-log.retention-days）；
	 *  oper_log 物理删除前把末条被删记录的 record_hash 落「截断锚点」，全量验签自锚点续起，清理不再误报断链。 */
	void cleanExpired() {
		doClean();
	}

	private java.util.Map<String, Integer> doClean() {
		String token = IdUtil.fastSimpleUUID();
		Boolean acquired = redis.opsForValue().setIfAbsent(MonitorConstants.CLEAN_LOCK_KEY, token,
			Duration.ofSeconds(MonitorConstants.CLEAN_LOCK_SECONDS));
		if (!Boolean.TRUE.equals(acquired)) {
			return java.util.Map.of();
		}
		try {
			LocalDateTime deadline = LocalDateTime.now().minusDays(clampedDays(
				paramService.getValue(MonitorConstants.PARAM_RETENTION_DAYS), MonitorConstants.DEFAULT_RETENTION_DAYS));
			int api = Db.deleteBySql("DELETE FROM " + com.mugsun.boot.config.BizTables.of("sys_api_log")
				+ " WHERE create_time < ?", deadline);
			int error = Db.deleteBySql("DELETE FROM " + com.mugsun.boot.config.BizTables.of("sys_error_log")
				+ " WHERE create_time < ?", deadline);
			int operDays = clampedDays(paramService.getValue(MonitorConstants.PARAM_OPER_RETENTION_DAYS),
				MonitorConstants.DEFAULT_OPER_RETENTION_DAYS);
			LocalDateTime operDeadline = LocalDateTime.now().minusDays(operDays);
			// 截断锚点：记录将被删除的末条记录（id 最大）哈希，验签从锚点续起（无锚点且首条 prev 非创世即报断链）
			com.mybatisflex.core.row.Row anchor = Db.selectOneBySql(
				"SELECT id, record_hash FROM " + com.mugsun.boot.config.BizTables.of("sys_oper_log")
					+ " WHERE create_time < ? AND record_hash IS NOT NULL "
					+ "ORDER BY id DESC" + com.mugsun.boot.gen.DbDialects.current().limitOne(), operDeadline);
			int oper = Db.deleteBySql("DELETE FROM " + com.mugsun.boot.config.BizTables.of("sys_oper_log")
				+ " WHERE create_time < ?", operDeadline);
			if (oper > 0 && anchor != null && anchor.getString("record_hash") != null) {
				paramService.setValue(MonitorConstants.PARAM_CHAIN_ANCHOR,
					anchor.getLong("id") + ":" + anchor.getString("record_hash"));
			}
			log.info("日志保留期清理完成：api_log {} 条，error_log {} 条，oper_log {} 条（api/error 保留 {} 天，oper 保留 {} 天）",
				api, error, oper, clampedDays(paramService.getValue(MonitorConstants.PARAM_RETENTION_DAYS),
					MonitorConstants.DEFAULT_RETENTION_DAYS), operDays);
			return java.util.Map.of("apiLog", api, "errorLog", error, "operLog", oper);
		} finally {
			// Lua compare-and-del 原子释放：TTL 过期被抢锁场景不误删他节点锁
			String script = "if redis.call('get', KEYS[1]) == ARGV[1] then return redis.call('del', KEYS[1]) else return 0 end";
			redis.execute(new org.springframework.data.redis.core.script.DefaultRedisScript<>(script, Long.class),
				java.util.List.of(MonitorConstants.CLEAN_LOCK_KEY), token);
		}
	}

	/** 保留期钳制 [1, 3650]：0/负值会删光全表，非法值回退默认（防误配事故） */
	private int clampedDays(String value, int def) {
		int days;
		try {
			days = value == null ? def : Integer.parseInt(value.trim());
		} catch (NumberFormatException e) {
			return def;
		}
		return days < 1 || days > 3650 ? def : days;
	}
}
