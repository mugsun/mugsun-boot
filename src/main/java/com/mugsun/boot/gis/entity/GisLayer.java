package com.mugsun.boot.gis.entity;

import com.mugsun.core.mybatis.base.BaseEntity;
import com.mybatisflex.annotation.Column;
import com.mybatisflex.annotation.Table;

import java.util.List;

/**
 * 可复用 GIS 图层：规范化后的 WGS84 GeoJSON，其它模块按同一格式写入即可叠加。
 */
@Table("gis_layer")
public class GisLayer extends BaseEntity {

	private String tenantId;
	private String name;
	private String kind;
	private String crs;
	private String dataJson;
	private String styleJson;
	private Integer featureCount;
	private String bbox;
	private Integer status;
	private String remark;
	/**
	 * 本次保存被自动修复的几何（未闭合环、自相交）。属于操作回执而非图层属性，
	 * 不落库、不参与查询，只在 submit 的响应里回一次，让前端能明确告知用户改了什么。
	 */
	@Column(ignore = true)
	private List<String> warnings;

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

	public String getKind() {
		return kind;
	}

	public void setKind(String kind) {
		this.kind = kind;
	}

	public String getCrs() {
		return crs;
	}

	public void setCrs(String crs) {
		this.crs = crs;
	}

	public String getDataJson() {
		return dataJson;
	}

	public void setDataJson(String dataJson) {
		this.dataJson = dataJson;
	}

	public String getStyleJson() {
		return styleJson;
	}

	public void setStyleJson(String styleJson) {
		this.styleJson = styleJson;
	}

	public Integer getFeatureCount() {
		return featureCount;
	}

	public void setFeatureCount(Integer featureCount) {
		this.featureCount = featureCount;
	}

	public String getBbox() {
		return bbox;
	}

	public void setBbox(String bbox) {
		this.bbox = bbox;
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

	public List<String> getWarnings() {
		return warnings;
	}

	public void setWarnings(List<String> warnings) {
		this.warnings = warnings;
	}
}
