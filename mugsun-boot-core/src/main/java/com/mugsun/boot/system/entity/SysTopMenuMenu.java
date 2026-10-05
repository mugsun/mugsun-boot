package com.mugsun.boot.system.entity;

import com.mugsun.core.mybatis.base.BaseEntity;
import com.mybatisflex.annotation.Table;

/**
 * 顶部菜单方案包含的菜单。租户归属由方案表决定。
 */
@Table("sys_top_menu_menu")
public class SysTopMenuMenu extends BaseEntity {

	private Long topMenuId;
	private Long menuId;

	public Long getTopMenuId() {
		return topMenuId;
	}

	public void setTopMenuId(Long topMenuId) {
		this.topMenuId = topMenuId;
	}

	public Long getMenuId() {
		return menuId;
	}

	public void setMenuId(Long menuId) {
		this.menuId = menuId;
	}
}
