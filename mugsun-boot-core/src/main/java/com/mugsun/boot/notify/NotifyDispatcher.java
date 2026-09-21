package com.mugsun.boot.notify;

import com.mugsun.boot.common.constant.NotifyConstants;
import com.mugsun.boot.notify.api.NotifyReceiver;
import com.mugsun.boot.notify.channel.MessageChannel;
import com.mugsun.boot.notify.channel.NotifyMessage;
import com.mugsun.boot.notify.entity.SysNotifyRecord;
import com.mugsun.boot.notify.mapper.SysNotifyRecordMapper;
import com.mugsun.boot.system.service.ParamService;
import com.mugsun.boot.tenant.TenantContext;
import com.mybatisflex.core.query.QueryWrapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 渠道 fan-out 投递器：按 INIT 流水逐条投递并异步回填回执（SUCCESS/FAILURE/IGNORE + 耗时 + 错误信息）。
 * 独立成 bean 解决 @Async 自调用代理失效问题；首次失败按线性退避排定下次重试时间。
 */
@Component
public class NotifyDispatcher {

	private static final Logger log = LoggerFactory.getLogger(NotifyDispatcher.class);

	private final SysNotifyRecordMapper recordMapper;
	private final ParamService paramService;
	private final Map<String, MessageChannel> channels;

	public NotifyDispatcher(SysNotifyRecordMapper recordMapper, ParamService paramService,
							List<MessageChannel> channelList) {
		this.recordMapper = recordMapper;
		this.paramService = paramService;
		this.channels = new HashMap<>();
		channelList.forEach(c -> this.channels.put(c.code(), c));
	}

	/** 异步 fan-out（虚拟线程执行器）：在调用方捕获的租户上下文中执行 */
	@Async
	public void dispatchAsync(Long batchId, String tenantId) {
		try {
			inTenant(tenantId, () -> dispatch(batchId));
		} catch (Exception e) {
			log.error("通知 fan-out 异常 batchId={}", batchId, e);
		}
	}

	/** 投递一个批次内的全部 INIT 流水（逐条隔离异常，单条失败不影响其余） */
	public void dispatch(Long batchId) {
		List<SysNotifyRecord> records = recordMapper.selectListByQuery(
			QueryWrapper.create().eq("batch_id", batchId).eq("status", NotifyConstants.STATUS_INIT));
		for (SysNotifyRecord record : records) {
			try {
				deliver(record, false, 0, 0);
			} catch (Exception e) {
				log.error("通知投递异常 recordId={} channel={}", record.getId(), record.getChannel(), e);
			}
		}
	}

	/**
	 * 重试单条 FAILURE 流水：次数递增，成功转 SUCCESS，再失败达上限转 DEAD，否则按线性退避排下次。
	 *
	 * @param maxTimes  最大重试次数（notify.retry.max-times）
	 * @param backoffMs 退避基数（notify.retry.backoff-ms）
	 */
	public void retryOne(SysNotifyRecord record, int maxTimes, long backoffMs) {
		record.setRetryCount(record.getRetryCount() + 1);
		deliver(record, true, maxTimes, backoffMs);
	}

	/** 单条投递与回执落库：retry=true 时按重试语义推进次数/退避/死信 */
	private void deliver(SysNotifyRecord record, boolean retry, int maxTimes, long backoffMs) {
		long start = System.currentTimeMillis();
		MessageChannel channel = channels.get(record.getChannel());
		try {
			if (channel == null || !channel.enabled()) {
				throw new UndeliverableException("渠道未启用或未配置: " + record.getChannel());
			}
			if (record.getReceiverContact() == null || record.getReceiverContact().isBlank()) {
				throw new UndeliverableException("接收人缺少联系方式");
			}
			NotifyReceiver receiver = NotifyReceiver.of(record.getReceiverId(), null, null, null);
			if (NotifyConstants.CHANNEL_MAIL.equals(record.getChannel())) {
				receiver.setEmail(record.getReceiverContact());
			} else if (NotifyConstants.CHANNEL_SMS.equals(record.getChannel())) {
				receiver.setPhone(record.getReceiverContact());
			}
			channel.send(new NotifyMessage(receiver, record.getSubject(), record.getContent()));
			record.setStatus(NotifyConstants.STATUS_SUCCESS);
			record.setErrorMsg("");
		} catch (UndeliverableException e) {
			// 不可投递（渠道停用/缺联系方式）：终态忽略，不重试
			record.setStatus(NotifyConstants.STATUS_IGNORE);
			record.setErrorMsg(NotifyService.truncate(e.getMessage(), NotifyConstants.ERROR_MSG_LEN));
		} catch (Exception e) {
			if (retry && record.getRetryCount() >= maxTimes) {
				record.setStatus(NotifyConstants.STATUS_DEAD);
				record.setErrorMsg(NotifyService.truncate("重试达上限: " + e.getMessage(), NotifyConstants.ERROR_MSG_LEN));
			} else {
				record.setStatus(NotifyConstants.STATUS_FAILURE);
				record.setErrorMsg(NotifyService.truncate(e.getMessage(), NotifyConstants.ERROR_MSG_LEN));
				long backoff = retry ? backoffMs * record.getRetryCount() : firstBackoffMs();
				record.setNextRetryTime(LocalDateTime.now().plusNanos(backoff * 1_000_000L));
			}
			log.warn("通知投递失败 recordId={} channel={} retryCount={} err={}",
				record.getId(), record.getChannel(), record.getRetryCount(), e.getMessage());
		}
		record.setCostMs(System.currentTimeMillis() - start);
		recordMapper.update(record);
	}

	/** 首次失败的退避取当前配置基数 ×1 */
	private long firstBackoffMs() {
		String value = paramService.getValue(NotifyConstants.PARAM_RETRY_BACKOFF);
		try {
			return value == null ? NotifyConstants.DEFAULT_RETRY_BACKOFF_MS : Long.parseLong(value.trim());
		} catch (NumberFormatException e) {
			return NotifyConstants.DEFAULT_RETRY_BACKOFF_MS;
		}
	}

	/** 在指定租户上下文执行；无租户（平台级事件）走忽略作用域 */
	static void inTenant(String tenantId, Runnable action) {
		if (tenantId == null || tenantId.isBlank()) {
			TenantContext.ignore(action);
		} else {
			TenantContext.execute(tenantId, action);
		}
	}

	/** 不可投递信号（区别于可重试的发送异常） */
	static class UndeliverableException extends RuntimeException {
		UndeliverableException(String message) {
			super(message);
		}
	}
}
