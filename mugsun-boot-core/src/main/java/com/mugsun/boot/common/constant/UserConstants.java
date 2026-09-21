package com.mugsun.boot.common.constant;

/**
 * 用户常量。
 */
public interface UserConstants {

	/** 内置管理员账号名 */
	String ADMIN_USERNAME = "admin";

	/** 用户下拉 /select 默认返回上限（成千账号场景配合 keyword 远程搜索，勿下全量） */
	int USER_SELECT_LIMIT = 50;
}
