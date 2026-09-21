package com.mugsun.boot.track.powerjob;

import com.mugsun.boot.track.job.TrackStats5mJob;
import com.mugsun.boot.track.job.TrackStatsDayJob;
import com.mugsun.boot.track.job.TrackVitalsJob;
import org.springframework.stereotype.Component;
import tech.powerjob.worker.core.processor.ProcessResult;
import tech.powerjob.worker.core.processor.TaskContext;
import tech.powerjob.worker.core.processor.sdk.BasicProcessor;

/**
 * 埋点聚合处理器：由 PowerJob 调度/手动触发，依次执行 5 分钟 / 天级 / vitals 直方图三轮 rollup。
 * 聚合逻辑（游标补扫 + 幂等覆盖 + 采样口径）复用各 Job 的立即执行方法，此处仅作调度入口。
 */
@Component
public class TrackStatsProcessor implements BasicProcessor {

	private final TrackStats5mJob stats5mJob;
	private final TrackStatsDayJob statsDayJob;
	private final TrackVitalsJob vitalsJob;

	public TrackStatsProcessor(TrackStats5mJob stats5mJob, TrackStatsDayJob statsDayJob, TrackVitalsJob vitalsJob) {
		this.stats5mJob = stats5mJob;
		this.statsDayJob = statsDayJob;
		this.vitalsJob = vitalsJob;
	}

	@Override
	public ProcessResult process(TaskContext context) {
		String summary = stats5mJob.rollupNow() + "；" + statsDayJob.rollupNow() + "；" + vitalsJob.rollupNow();
		context.getOmsLogger().info(summary);
		return new ProcessResult(true, summary);
	}
}
