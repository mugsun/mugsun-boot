package com.mugsun.boot.common.constant;

/**
 * 客户端常量。
 */
public interface ClientConstants {

	/** 默认登录客户端编码 */
	String DEFAULT_CLIENT_ID = "web";

	/** 移动工作台客户端编码（sys_client.app；登录走 /app/** + 滑块 ticket） */
	String APP_CLIENT_ID = "app";
}
