package com.mugsun.boot.job;

import com.mugsun.boot.monitor.LogCleanJob;
import org.springframework.stereotype.Component;
import tech.powerjob.worker.core.processor.ProcessResult;
import tech.powerjob.worker.core.processor.TaskContext;
import tech.powerjob.worker.core.processor.sdk.BasicProcessor;

import java.util.Map;

/**
 * 日志保留期清理处理器：由 PowerJob 调度/手动触发，执行平台日志（访问/错误/操作）超期物理清理。
 * 清理逻辑复用 {@link LogCleanJob}（分布式锁 + 截断锚点），此处仅作调度入口。
 */
@Component
public class LogCleanProcessor implements BasicProcessor {

	private final LogCleanJob logCleanJob;

	public LogCleanProcessor(LogCleanJob logCleanJob) {
		this.logCleanJob = logCleanJob;
	}

	@Override
	public ProcessResult process(TaskContext context) {
		Map<String, Integer> counts = logCleanJob.cleanNow();
		String summary = "清理完成：api_log " + counts.getOrDefault("apiLog", 0)
			+ " 条，error_log " + counts.getOrDefault("errorLog", 0)
			+ " 条，oper_log " + counts.getOrDefault("operLog", 0) + " 条";
		context.getOmsLogger().info(summary);
		return new ProcessResult(true, summary);
	}
}
