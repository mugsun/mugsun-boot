package com.mugsun.boot.track.powerjob;

import com.mugsun.boot.demo.ConditionalOnNotDemo;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mugsun.boot.track.job.TrackUvBackfillJob;
import org.springframework.stereotype.Component;
import tech.powerjob.worker.core.processor.ProcessResult;
import tech.powerjob.worker.core.processor.TaskContext;
import tech.powerjob.worker.core.processor.sdk.BasicProcessor;

/**
 * 历史 UV 回填处理器（手动触发，可重复执行）。任务参数可选 JSON：{@code {"days":90,"appKey":"xxx"}}，
 * 留空按 {@code mugsun.track.uv-backfill.days}（默认 90）回填全部应用。
 */
@Component
@ConditionalOnNotDemo
public class TrackUvBackfillProcessor implements BasicProcessor {

	private final TrackUvBackfillJob job;
	private final ObjectMapper objectMapper = new ObjectMapper();

	public TrackUvBackfillProcessor(TrackUvBackfillJob job) {
		this.job = job;
	}

	@Override
	public ProcessResult process(TaskContext context) throws Exception {
		String params = context.getJobParams();
		int days = job.defaultDays();
		String appKey = null;
		if (params != null && !params.isBlank()) {
			JsonNode node = objectMapper.readTree(params);
			days = node.path("days").asInt(days);
			appKey = node.path("appKey").asText(null);
		}
		TrackUvBackfillJob.Result result = job.backfill(days, appKey);
		context.getOmsLogger().info(result.summary());
		// 只有 DONE 算成功：窗外、暂停、锁丢失、部分超时都标未完成，提醒运维继续每天定时直到出现“UV 回填完成”
		return new ProcessResult(result.completed(), result.summary());
	}
}
