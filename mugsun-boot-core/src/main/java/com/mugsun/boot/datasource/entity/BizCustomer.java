package com.mugsun.boot.datasource.entity;

import com.mugsun.core.mybatis.base.BaseEntity;
import com.mybatisflex.annotation.Table;

/**
 * 演示业务实体：客户。按当前租户的独立数据源配置路由到对应库。
 */
@Table("biz_customer")
public class BizCustomer extends BaseEntity {

	private String tenantId;
	private String name;
	private String phone;
	private String remark;

	public String getTenantId() {
		return tenantId;
	}

	public void setTenantId(String tenantId) {
		this.tenantId = tenantId;
	}

	public String getName() {
		return name;
	}

	public void setName(String name) {
		this.name = name;
	}

	public String getPhone() {
		return phone;
	}

	public void setPhone(String phone) {
		this.phone = phone;
	}

	public String getRemark() {
		return remark;
	}

	public void setRemark(String remark) {
		this.remark = remark;
	}
}
