package com.mugsun.boot.websocket;

import com.mugsun.boot.common.constant.WsConstants;

/**
 * Redis 扇出广播消息：推送意图的载体，各节点订阅后按 targetType 本地投递。
 */
public class WsBroadcastMessage {

	/** 目标类型：user/tenant/all/close（见 WsConstants TARGET_*） */
	private String targetType;
	/** 目标用户（targetType=user；close 按账号全端时） */
	private Long userId;
	/** 目标租户（targetType=tenant） */
	private String tenantId;
	/** 目标令牌（close 按单端时） */
	private String tokenValue;
	/** 关闭原因（targetType=close） */
	private String reason;
	/** 推送帧（targetType=close 时为空） */
	private WsFrame frame;

	public static WsBroadcastMessage ofUser(Long userId, WsFrame frame) {
		WsBroadcastMessage msg = new WsBroadcastMessage();
		msg.setTargetType(WsConstants.TARGET_USER);
		msg.setUserId(userId);
		msg.setFrame(frame);
		return msg;
	}

	public static WsBroadcastMessage ofTenant(String tenantId, WsFrame frame) {
		WsBroadcastMessage msg = new WsBroadcastMessage();
		msg.setTargetType(WsConstants.TARGET_TENANT);
		msg.setTenantId(tenantId);
		msg.setFrame(frame);
		return msg;
	}

	public static WsBroadcastMessage ofAll(WsFrame frame) {
		WsBroadcastMessage msg = new WsBroadcastMessage();
		msg.setTargetType(WsConstants.TARGET_ALL);
		msg.setFrame(frame);
		return msg;
	}

	public static WsBroadcastMessage ofClose(Long userId, String tokenValue, String reason) {
		WsBroadcastMessage msg = new WsBroadcastMessage();
		msg.setTargetType(WsConstants.TARGET_CLOSE);
		msg.setUserId(userId);
		msg.setTokenValue(tokenValue);
		msg.setReason(reason);
		return msg;
	}

	public String getTargetType() {
		return targetType;
	}

	public void setTargetType(String targetType) {
		this.targetType = targetType;
	}

	public Long getUserId() {
		return userId;
	}

	public void setUserId(Long userId) {
		this.userId = userId;
	}

	public String getTenantId() {
		return tenantId;
	}

	public void setTenantId(String tenantId) {
		this.tenantId = tenantId;
	}

	public String getTokenValue() {
		return tokenValue;
	}

	public void setTokenValue(String tokenValue) {
		this.tokenValue = tokenValue;
	}

	public String getReason() {
		return reason;
	}

	public void setReason(String reason) {
		this.reason = reason;
	}

	public WsFrame getFrame() {
		return frame;
	}

	public void setFrame(WsFrame frame) {
		this.frame = frame;
	}
}
