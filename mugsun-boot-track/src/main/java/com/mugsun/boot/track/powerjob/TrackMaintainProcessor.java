package com.mugsun.boot.track.powerjob;

import com.mugsun.boot.track.job.TrackPartitionJob;
import com.mugsun.boot.track.job.TrackSessionSettleJob;
import org.springframework.stereotype.Component;
import tech.powerjob.worker.core.processor.ProcessResult;
import tech.powerjob.worker.core.processor.TaskContext;
import tech.powerjob.worker.core.processor.sdk.BasicProcessor;

/**
 * 埋点维护处理器：由 PowerJob 调度/手动触发，执行会话结算（静默会话定稿）与分区生命周期维护。
 * 维护逻辑（分布式锁 + 幂等）复用各 Job 的立即执行方法，此处仅作调度入口。
 */
@Component
public class TrackMaintainProcessor implements BasicProcessor {

	private final TrackSessionSettleJob settleJob;
	private final TrackPartitionJob partitionJob;

	public TrackMaintainProcessor(TrackSessionSettleJob settleJob, TrackPartitionJob partitionJob) {
		this.settleJob = settleJob;
		this.partitionJob = partitionJob;
	}

	@Override
	public ProcessResult process(TaskContext context) {
		int settled = settleJob.settleNow();
		String partition = partitionJob.maintainNow();
		String summary = "会话结算定稿 " + settled + " 个；" + partition;
		context.getOmsLogger().info(summary);
		return new ProcessResult(true, summary);
	}
}
