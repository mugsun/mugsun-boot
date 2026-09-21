package com.mugsun.boot.gis.entity;

import com.mugsun.core.mybatis.base.BaseEntity;
import com.mybatisflex.annotation.Table;

/**
 * GIS 场景：scene_json 存 viewMode / baseMap / view2d / view3d / layers。
 */
@Table("gis_scene")
public class GisScene extends BaseEntity {

	private String tenantId;
	private String name;
	private String sceneJson;
	private Integer status;
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

	public String getSceneJson() {
		return sceneJson;
	}

	public void setSceneJson(String sceneJson) {
		this.sceneJson = sceneJson;
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
