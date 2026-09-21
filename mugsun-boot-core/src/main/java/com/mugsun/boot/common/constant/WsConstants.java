package com.mugsun.boot.common.constant;

/**
 * WebSocket 实时推送常量。
 */
public interface WsConstants {

	/** 端点路径（前端直连，登录令牌经 query 参数传递） */
	String WS_PATH = "/ws/message";

	/** 握手 query 中携带登录令牌（tokenValue）的参数名 */
	String TOKEN_PARAM = "token";

	/** 集群扇出 Redis Pub/Sub 频道 */
	String REDIS_CHANNEL = "mugsun:ws:broadcast";

	/** 帧类型：新站内信 */
	String MESSAGE_NEW = "message.new";
	/** 帧类型：新公告 */
	String NOTICE_NEW = "notice.new";
	/** 帧类型：强制下线 */
	String FORCE_OFFLINE = "force.offline";

	/** 心跳：客户端探活（文本帧原文） */
	String PING = "ping";
	/** 心跳：服务端应答（文本帧原文） */
	String PONG = "pong";

	/** 会话属性键：用户 id（Long） */
	String ATTR_USER_ID = "wsUserId";
	/** 会话属性键：租户编号（String，可缺省） */
	String ATTR_TENANT_ID = "wsTenantId";
	/** 会话属性键：本端登录令牌 */
	String ATTR_TOKEN_VALUE = "wsTokenValue";

	/** 广播目标：指定用户 */
	String TARGET_USER = "user";
	/** 广播目标：指定租户 */
	String TARGET_TENANT = "tenant";
	/** 广播目标：全体在线 */
	String TARGET_ALL = "all";
	/** 广播目标：关闭连接（强制下线） */
	String TARGET_CLOSE = "close";

	/** 发送器类型配置键 */
	String SENDER_TYPE_PROPERTY = "mugsun.websocket.sender-type";
	/** 发送器类型：单机直发（仅本地调试） */
	String SENDER_TYPE_LOCAL = "local";
	/** 发送器类型：集群经 Redis Pub/Sub 扇出 */
	String SENDER_TYPE_REDIS = "redis";

	/** 单帧发送超时（毫秒），超时视为该端失联 */
	long SEND_TIMEOUT_MS = 5000L;
	/** 单连接发送缓冲上限（字节），防慢客户端拖垮内存 */
	int SEND_BUFFER_BYTES = 102400;
}
