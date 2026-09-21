package com.mugsun.boot.form.entity;

import com.mugsun.core.mybatis.base.BaseEntity;
import com.mybatisflex.annotation.Table;

/**
 * 低代码表单填报数据。
 */
@Table("sys_form_data")
public class SysFormData extends BaseEntity {

	private String formKey;
	/** 填报数据 JSON */
	private String formData;
	private Long submitter;

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
}
