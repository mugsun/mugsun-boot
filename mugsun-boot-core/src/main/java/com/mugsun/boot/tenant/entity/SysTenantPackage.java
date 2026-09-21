package com.mugsun.boot.tenant.entity;

import com.mugsun.core.mybatis.base.BaseEntity;
import com.mybatisflex.annotation.Table;

/**
 * 租户套餐：限定该套餐下租户可用的功能菜单。
 * 无 tenant_id 列——平台级全局配置，不参与租户隔离。
 */
@Table("sys_tenant_package")
public class SysTenantPackage extends BaseEntity {

	private String name;
	/** 套餐内可用菜单标识（前端路由 name，逗号分隔） */
	private String menuKeys;
	private Integer status;
	private String remark;

	public String getName() {
		return name;
	}

	public void setName(String name) {
		this.name = name;
	}

	public String getMenuKeys() {
		return menuKeys;
	}

	public void setMenuKeys(String menuKeys) {
		this.menuKeys = menuKeys;
	}

	public Integer getStatus() {
		return status;
	}

	public void setStatus(Integer status) {
		this.status = status;
	}

	public String getRemark() {
		return remark;
	}

	public void setRemark(String remark) {
		this.remark = remark;
	}
}
