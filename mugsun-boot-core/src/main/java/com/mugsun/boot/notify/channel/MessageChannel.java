package com.mugsun.boot.notify.channel;

/**
 * 消息渠道抽象：站内信/邮件/短信已实现，微信公众号等新渠道实现本接口即可接入（扩展点）。
 */
public interface MessageChannel {

	/** 渠道编码（sys_notify_channel.channel / 模板 channels 列取值） */
	String code();

	/** 渠道是否启用（读 sys_notify_channel 配置行，库表驱动热更新） */
	boolean enabled();

	/**
	 * 投递单条报文。发送失败必须抛异常（调度侧据此记 FAILURE 并安排重试）；
	 * 接收人缺联系方式等不可投递情况亦抛异常，由调度侧归类 IGNORE。
	 */
	void send(NotifyMessage message);
}
