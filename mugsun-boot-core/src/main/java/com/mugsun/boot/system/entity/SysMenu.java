package com.mugsun.boot.system.entity;

import com.mugsun.core.mybatis.base.BaseEntity;
import com.mugsun.core.tool.tree.INode;
import com.mybatisflex.annotation.Column;
import com.mybatisflex.annotation.Table;

import java.util.List;

/**
 * 菜单 / 按钮权限
 */
@Table("sys_menu")
public class SysMenu extends BaseEntity implements INode<SysMenu> {

	private Long parentId;
	private String menuName;
	private String path;
	private String component;
	private String menuType;
	private String permission;
	private Integer sort;
	/** 图标（Iconify 名，如 ri:user-line） */
	private String icon;
	/** 是否公共菜单（0 按角色授权 / 1 任意登录可见） */
	private Integer isPublic = 0;
	/** 是否隐藏（0 显示 / 1 隐藏）；Java 侧默认 0，防 insert 未显式赋值时触 NOT NULL 约束 */
	private Integer isHide = 0;
	/** 是否缓存页面（0 不缓存 / 1 缓存）；Java 侧默认 1 */
	private Integer isKeepAlive = 1;
	/** 是否外链（0 否 / 1 是，新窗口打开）；Java 侧默认 0 */
	private Integer isExternal = 0;

	@Column(ignore = true)
	private List<SysMenu> children;

	@Override
	public Long getParentId() {
		return parentId;
	}

	public void setParentId(Long parentId) {
		this.parentId = parentId;
	}

	public String getMenuName() {
		return menuName;
	}

	public void setMenuName(String menuName) {
		this.menuName = menuName;
	}

	public String getPath() {
		return path;
	}

	public void setPath(String path) {
		this.path = path;
	}

	public String getComponent() {
		return component;
	}

	public void setComponent(String component) {
		this.component = component;
	}

	public String getMenuType() {
		return menuType;
	}

	public void setMenuType(String menuType) {
		this.menuType = menuType;
	}

	public String getPermission() {
		return permission;
	}

	public void setPermission(String permission) {
		this.permission = permission;
	}

	public Integer getSort() {
		return sort;
	}

	public void setSort(Integer sort) {
		this.sort = sort;
	}

	public String getIcon() {
		return icon;
	}

	public void setIcon(String icon) {
		this.icon = icon;
	}

	public Integer getIsPublic() {
		return isPublic;
	}

	public void setIsPublic(Integer isPublic) {
		this.isPublic = isPublic;
	}

	public Integer getIsHide() {
		return isHide;
	}

	public void setIsHide(Integer isHide) {
		this.isHide = isHide;
	}

	public Integer getIsKeepAlive() {
		return isKeepAlive;
	}

	public void setIsKeepAlive(Integer isKeepAlive) {
		this.isKeepAlive = isKeepAlive;
	}

	public Integer getIsExternal() {
		return isExternal;
	}

	public void setIsExternal(Integer isExternal) {
		this.isExternal = isExternal;
	}

	@Override
	public List<SysMenu> getChildren() {
		return children;
	}

	@Override
	public void setChildren(List<SysMenu> children) {
		this.children = children;
	}
}
