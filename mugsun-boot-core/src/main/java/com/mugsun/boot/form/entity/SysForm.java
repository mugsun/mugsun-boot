package com.mugsun.boot.form.entity;

import com.mugsun.core.mybatis.base.BaseEntity;
import com.mybatisflex.annotation.Table;

/**
 * 低代码表单定义（form-create schema）。
 */
@Table("sys_form")
public class SysForm extends BaseEntity {

	private String name;
	private String formKey;
	/** form-create 规则 JSON */
	private String formSchema;
	/** form-create 配置 JSON */
	private String formOption;
	private Integer status;
	private String remark;

	public String getName() {
		return name;
	}

	public void setName(String name) {
		this.name = name;
	}

	public String getFormKey() {
		return formKey;
	}

	public void setFormKey(String formKey) {
		this.formKey = formKey;
	}

	public String getFormSchema() {
		return formSchema;
	}

	public void setFormSchema(String formSchema) {
		this.formSchema = formSchema;
	}

	public String getFormOption() {
		return formOption;
	}

	public void setFormOption(String formOption) {
		this.formOption = formOption;
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
