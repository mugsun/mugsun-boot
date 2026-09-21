package com.mugsun.boot.tablecolumn.entity;

import com.mugsun.core.mybatis.base.BaseEntity;
import com.mybatisflex.annotation.Table;

/**
 * 表格自定义列配置：按 user_id + table_key 唯一，config_json 存列顺序/显隐/宽。
 */
@Table("sys_table_column")
public class SysTableColumn extends BaseEntity {

	/** 所属用户 */
	private Long userId;
	/** 表格标识（前端页面唯一 key） */
	private String tableKey;
	/** 列配置 JSON：[{key,visible,width}]，顺序即数组序 */
	private String configJson;

	public Long getUserId() {
		return userId;
	}

	public void setUserId(Long userId) {
		this.userId = userId;
	}

	public String getTableKey() {
		return tableKey;
	}

	public void setTableKey(String tableKey) {
		this.tableKey = tableKey;
	}

	public String getConfigJson() {
		return configJson;
	}

	public void setConfigJson(String configJson) {
		this.configJson = configJson;
	}
}
