package com.mugsun.boot.workbench.entity;

import com.mugsun.core.mybatis.base.BaseEntity;
import com.mybatisflex.annotation.Table;

/**
 * 工作台快捷入口：按 user_id 唯一，config_json 存 [{name,path}] 数组。
 */
@Table("sys_workbench_shortcut")
public class SysWorkbenchShortcut extends BaseEntity {

	/** 所属用户 */
	private Long userId;
	/** 快捷入口 JSON：[{name,path}]，顺序即数组序 */
	private String configJson;

	public Long getUserId() {
		return userId;
	}

	public void setUserId(Long userId) {
		this.userId = userId;
	}

	public String getConfigJson() {
		return configJson;
	}

	public void setConfigJson(String configJson) {
		this.configJson = configJson;
	}
}
