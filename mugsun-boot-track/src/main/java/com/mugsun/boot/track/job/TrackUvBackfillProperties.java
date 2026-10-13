package com.mugsun.boot.track.job;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.LocalTime;
import java.util.LinkedHashMap;
import java.util.Map;

/** 历史 UV 回填配置（mugsun.track.uv-backfill.*） */
@Component
@ConfigurationProperties(prefix = "mugsun.track.uv-backfill")
public class TrackUvBackfillProperties {

	/** 回填最近多少个闭合日（UTC） */
	private int days = 90;
	/** 每处理完一个 app×天 的间隔（毫秒），给线上库喘息 */
	private long sleepMillis = 300;
	/** 单个 app×天 事务内 statement_timeout（秒） */
	private int statementTimeoutSeconds = 120;
	/**
	 * 按 appKey 覆盖单个 app×天 的 statement_timeout；未配置的应用用 statementTimeoutSeconds。
	 * <b>键必须加中括号</b>（yml 里再加引号），如 {@code "[MyApp_1]": 600s}；不加中括号会被 Spring 小写化并去掉 . _ 等字符，
	 * 对不上 appKey（启动时会打 WARN）。
	 */
	private Map<String, Duration> appStatementTimeout = new LinkedHashMap<>();
	/** 单个 app×天 事务内 SET LOCAL work_mem（如 256MB，单位 kB/MB/GB）；留空不设置，沿用库默认 */
	private String workMem = "256MB";
	/** 回填独立锁 TTL（秒）；每处理完一个 app×天 续期 */
	private long lockTtlSeconds = 600;
	/** 是否只在运行时段内执行 */
	private boolean windowEnabled = true;
	/** 运行时段起（含），按 windowZone */
	private String windowStart = "02:00";
	/** 运行时段止（不含），按 windowZone */
	private String windowEnd = "07:00";
	/** 运行时段时区 */
	private String windowZone = "Asia/Shanghai";
	/** 启动后后台跑一次（高峰期不要开） */
	private boolean onStartup = false;

	public int getDays() { return days; }
	public void setDays(int days) { this.days = days; }
	public long getSleepMillis() { return sleepMillis; }
	public void setSleepMillis(long sleepMillis) { this.sleepMillis = sleepMillis; }
	public int getStatementTimeoutSeconds() { return statementTimeoutSeconds; }
	public void setStatementTimeoutSeconds(int s) { this.statementTimeoutSeconds = s; }
	public Map<String, Duration> getAppStatementTimeout() { return appStatementTimeout; }
	public void setAppStatementTimeout(Map<String, Duration> m) { this.appStatementTimeout = m; }
	public String getWorkMem() { return workMem; }
	public void setWorkMem(String workMem) { this.workMem = workMem; }
	public long getLockTtlSeconds() { return lockTtlSeconds; }
	public void setLockTtlSeconds(long lockTtlSeconds) { this.lockTtlSeconds = lockTtlSeconds; }
	public boolean isWindowEnabled() { return windowEnabled; }
	public void setWindowEnabled(boolean windowEnabled) { this.windowEnabled = windowEnabled; }
	public String getWindowStart() { return windowStart; }
	public LocalTime windowStartTime() { return LocalTime.parse(windowStart); }
	public void setWindowStart(String windowStart) { this.windowStart = windowStart; }
	public String getWindowEnd() { return windowEnd; }
	public LocalTime windowEndTime() { return LocalTime.parse(windowEnd); }
	public void setWindowEnd(String windowEnd) { this.windowEnd = windowEnd; }
	public String getWindowZone() { return windowZone; }
	public void setWindowZone(String windowZone) { this.windowZone = windowZone; }
	public boolean isOnStartup() { return onStartup; }
	public void setOnStartup(boolean onStartup) { this.onStartup = onStartup; }
}
