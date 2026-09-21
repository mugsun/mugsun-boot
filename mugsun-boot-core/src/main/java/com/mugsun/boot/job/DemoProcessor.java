package com.mugsun.boot.job;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import tech.powerjob.worker.core.processor.ProcessResult;
import tech.powerjob.worker.core.processor.TaskContext;
import tech.powerjob.worker.core.processor.sdk.BasicProcessor;

/**
 * 示例任务处理器：由 PowerJob 调度执行。
 */
@Component
public class DemoProcessor implements BasicProcessor {

	private static final Logger log = LoggerFactory.getLogger(DemoProcessor.class);

	@Override
	public ProcessResult process(TaskContext context) {
		log.info("【PowerJob】任务执行 jobParams={} instanceParams={}",
			context.getJobParams(), context.getInstanceParams());
		context.getOmsLogger().info("Mugsun 定时任务执行成功，实例ID={}", context.getInstanceId());
		return new ProcessResult(true, "执行成功");
	}
}
