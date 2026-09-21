package com.mugsun.boot.websocket;

/**
 * 下行推送帧：type 见 WsConstants 帧类型，content 为随类型而定的 JSON 对象（序列化一次，不二次编码）。
 */
public class WsFrame {

	/** 帧类型 */
	private String type;
	/** 帧内容（直接序列化为 JSON 对象） */
	private Object content;

	public WsFrame() {
	}

	public WsFrame(String type, Object content) {
		this.type = type;
		this.content = content;
	}

	public static WsFrame of(String type, Object content) {
		return new WsFrame(type, content);
	}

	public String getType() {
		return type;
	}

	public void setType(String type) {
		this.type = type;
	}

	public Object getContent() {
		return content;
	}

	public void setContent(Object content) {
		this.content = content;
	}
}
