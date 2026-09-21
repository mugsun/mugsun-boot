package com.mugsun.boot.websocket;

import java.util.Collection;

/**
 * 推送门面：业务只面向此接口，单机直发 / 集群 Redis 扇出由配置切换（mugsun.websocket.sender-type）。
 */
public interface WsMessageSender {

	/** 推给指定用户（全部在线端） */
	void sendToUser(Long userId, WsFrame frame);

	/** 推给一组用户（各自全部在线端） */
	void sendToUsers(Collection<Long> userIds, WsFrame frame);

	/** 推给指定租户的全部在线用户 */
	void sendToTenant(String tenantId, WsFrame frame);

	/** 推给全部在线用户 */
	void sendToAll(WsFrame frame);

	/** 断开用户推送连接：tokenValue 非空按令牌踢单端，否则按账号踢全端 */
	void closeUser(Long userId, String tokenValue, String reason);
}
