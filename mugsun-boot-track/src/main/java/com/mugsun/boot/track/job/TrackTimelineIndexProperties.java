package com.mugsun.boot.track.job;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.time.LocalTime;

/** 时间线分区索引后台补建配置（mugsun.track.timeline-index.*） */
@Component
@ConfigurationProperties(prefix = "mugsun.track.timeline-index")
public class TrackTimelineIndexProperties {

	/** 是否允许后台给存量非空分区补建索引（关掉后只靠 T4 / 分区任务处理空分区与新分区） */
	private boolean backfillEnabled = true;
	/** 两个分区之间的停顿（毫秒），给线上库喘息 */
	private long pauseMillis = 5000;
	/** 是否只在运行时段内执行 */
	private boolean windowEnabled = true;
	/** 运行时段起（含），按 windowZone */
	private String windowStart = "02:00";
	/** 运行时段止（不含）；只在开始下一个分区前检查，单个分区建到一半不会中断 */
	private String windowEnd = "06:00";
	/** 运行时段时区 */
	private String windowZone = "Asia/Shanghai";

	public boolean isBackfillEnabled() { return backfillEnabled; }
	public void setBackfillEnabled(boolean backfillEnabled) { this.backfillEnabled = backfillEnabled; }
	public long getPauseMillis() { return pauseMillis; }
	public void setPauseMillis(long pauseMillis) { this.pauseMillis = pauseMillis; }
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
}
