package com.mugsun.boot.form.entity;

import com.mugsun.core.mybatis.base.BaseEntity;
import com.mybatisflex.annotation.Column;
import com.mybatisflex.annotation.Table;

/**
 * 低代码表单填报数据。
 */
@Table("sys_form_data")
public class SysFormData extends BaseEntity {

	/** 与所属表单相同 */
	private String tenantId;
	private String formKey;
	/** 填报数据 JSON */
	private String formData;
	private Long submitter;
	/** 填报人展示名（非表列） */
	@Column(ignore = true)
	private String submitterName;

	public String getTenantId() {
		return tenantId;
	}

	public void setTenantId(String tenantId) {
		this.tenantId = tenantId;
	}

	public String getFormKey() {
		return formKey;
	}

	public void setFormKey(String formKey) {
		this.formKey = formKey;
	}

	public String getFormData() {
		return formData;
	}

	public void setFormData(String formData) {
		this.formData = formData;
	}

	public Long getSubmitter() {
		return submitter;
	}

	public void setSubmitter(Long submitter) {
		this.submitter = submitter;
	}

	public String getSubmitterName() {
		return submitterName;
	}

	public void setSubmitterName(String submitterName) {
		this.submitterName = submitterName;
	}
}
