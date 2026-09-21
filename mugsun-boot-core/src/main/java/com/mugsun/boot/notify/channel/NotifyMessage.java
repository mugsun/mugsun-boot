package com.mugsun.boot.notify.channel;

import com.mugsun.boot.notify.api.NotifyReceiver;

/**
 * 渠道发送报文：渲染后的主题/内容 + 接收人。
 */
public class NotifyMessage {

	private final NotifyReceiver receiver;
	private final String title;
	private final String content;

	public NotifyMessage(NotifyReceiver receiver, String title, String content) {
		this.receiver = receiver;
		this.title = title;
		this.content = content;
	}

	public NotifyReceiver getReceiver() {
		return receiver;
	}

	public String getTitle() {
		return title;
	}

	public String getContent() {
		return content;
	}
}
