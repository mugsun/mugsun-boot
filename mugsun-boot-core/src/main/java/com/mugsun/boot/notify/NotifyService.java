package com.mugsun.boot.notify;

import cn.hutool.core.util.IdUtil;
import com.mugsun.boot.common.constant.NotifyConstants;
import com.mugsun.boot.common.tx.AfterCommit;
import com.mugsun.boot.notify.api.NotifyReceiver;
import com.mugsun.boot.notify.entity.SysNotifyRecord;
import com.mugsun.boot.notify.entity.SysNotifyTemplate;
import com.mugsun.boot.notify.mapper.SysNotifyRecordMapper;
import com.mugsun.boot.notify.mapper.SysNotifyTemplateMapper;
import com.mugsun.boot.tenant.TenantContext;
import com.mugsun.core.tool.exception.ServiceException;
import com.mybatisflex.core.query.QueryWrapper;
import org.springframework.stereotype.Service;

import java.util.Arrays;
import java.util.List;
import java.util.Map;

/**
 * 统一通知编排：模板渲染（缺参 fail-fast）→ 逐（渠道 × 接收人）落 INIT 流水 → 提交后异步 fan-out。
 * 渲染校验在调用线程同步完成（参数错误立即暴露），渠道投递异步执行（不拖慢业务主流程）。
 */
@Service
public class NotifyService {

	private final SysNotifyTemplateMapper templateMapper;
	private final SysNotifyRecordMapper recordMapper;
	private final NotifyTemplateRenderer renderer;
	private final NotifyDispatcher dispatcher;

	public NotifyService(SysNotifyTemplateMapper templateMapper, SysNotifyRecordMapper recordMapper,
						 NotifyTemplateRenderer renderer, NotifyDispatcher dispatcher) {
		this.templateMapper = templateMapper;
		this.recordMapper = recordMapper;
		this.renderer = renderer;
		this.dispatcher = dispatcher;
	}

	/** 见 {@link com.mugsun.boot.notify.api.NotifySendApi#send} 契约 */
	public void send(String templateCode, List<NotifyReceiver> receivers, Map<String, String> params, String... channels) {
		if (receivers == null || receivers.isEmpty()) {
			throw new ServiceException("通知接收人不能为空");
		}
		SysNotifyTemplate template = templateMapper.selectOneByQuery(
			QueryWrapper.create().eq("code", templateCode).eq("status", 1));
		if (template == null) {
			throw new ServiceException("通知模板不存在或未启用: " + templateCode);
		}
		// 缺参 fail-fast：落流水前校验，拒绝发出残缺通知
		String subject = renderer.render(template.getSubject(), params);
		String content = renderer.render(template.getContent(), params);
		List<String> channelCodes = resolveChannels(template, channels);
		if (channelCodes.isEmpty()) {
			throw new ServiceException("通知模板未配置投递渠道: " + templateCode);
		}

		long batchId = IdUtil.getSnowflakeNextId();
		// 捕获调用线程租户，供异步 fan-out 重放（虚拟线程上无登录会话，TenantTaskDecorator 仅透传显式 ThreadLocal）
		String tenantId = TenantContext.current();
		for (String channelCode : channelCodes) {
			for (NotifyReceiver receiver : receivers) {
				SysNotifyRecord record = new SysNotifyRecord();
				record.setBatchId(batchId);
				record.setTemplateCode(templateCode);
				record.setChannel(channelCode);
				record.setReceiverId(receiver.getUserId());
				record.setReceiverContact(contactOf(channelCode, receiver));
				record.setSubject(subject);
				record.setContent(content);
				record.setContentSummary(truncate(content, NotifyConstants.CONTENT_SUMMARY_LEN));
				record.setStatus(NotifyConstants.STATUS_INIT);
				record.setRetryCount(0);
				recordMapper.insertSelective(record);
			}
		}
		// 提交后异步 fan-out：业务事务回滚则不投递（流水随事务一致）；无事务时立即触发（@Async 仍异步）
		AfterCommit.execute(() -> dispatcher.dispatchAsync(batchId, tenantId));
	}

	/** 入参渠道优先；为空按模板默认 channels */
	private List<String> resolveChannels(SysNotifyTemplate template, String... channels) {
		if (channels != null && channels.length > 0) {
			return Arrays.stream(channels).filter(c -> c != null && !c.isBlank()).toList();
		}
		String configured = template.getChannels();
		if (configured == null || configured.isBlank()) {
			return List.of();
		}
		return Arrays.stream(configured.split(NotifyConstants.SPLIT))
			.map(String::trim).filter(c -> !c.isBlank()).toList();
	}

	/** 各渠道联系方式快照：站内信=用户id，邮件=邮箱，短信=手机号 */
	private String contactOf(String channelCode, NotifyReceiver receiver) {
		return switch (channelCode) {
			case NotifyConstants.CHANNEL_IN_APP ->
				receiver.getUserId() == null ? null : receiver.getUserId().toString();
			case NotifyConstants.CHANNEL_MAIL -> receiver.getEmail();
			case NotifyConstants.CHANNEL_SMS -> receiver.getPhone();
			default -> null;
		};
	}

	static String truncate(String text, int max) {
		if (text == null || text.length() <= max) {
			return text;
		}
		return text.substring(0, max);
	}
}
