package com.mugsun.boot.system.excel;

import cn.idev.excel.annotation.ExcelProperty;

/**
 * 用户导入模型（兼导入模板）：仅暴露非敏感列，密码等不参与 Excel。
 * 部门/岗位按名称导入（服务端按名解析 id），导出模型见 {@link SysUserExportExcel}。
 */
public class SysUserExcel {

	@ExcelProperty("用户名")
	private String username;

	@ExcelProperty("昵称")
	private String nickname;

	@ExcelProperty("部门")
	private String deptName;

	@ExcelProperty("岗位")
	private String postName;

	@ExcelProperty("邮箱")
	private String email;

	@ExcelProperty("手机")
	private String phone;

	@ExcelProperty("状态(1启用/0禁用)")
	private Integer status;

	public String getUsername() {
		return username;
	}

	public void setUsername(String username) {
		this.username = username;
	}

	public String getNickname() {
		return nickname;
	}

	public void setNickname(String nickname) {
		this.nickname = nickname;
	}

	public String getDeptName() {
		return deptName;
	}

	public void setDeptName(String deptName) {
		this.deptName = deptName;
	}

	public String getPostName() {
		return postName;
	}

	public void setPostName(String postName) {
		this.postName = postName;
	}

	public String getEmail() {
		return email;
	}

	public void setEmail(String email) {
		this.email = email;
	}

	public String getPhone() {
		return phone;
	}

	public void setPhone(String phone) {
		this.phone = phone;
	}

	public Integer getStatus() {
		return status;
	}

	public void setStatus(Integer status) {
		this.status = status;
	}
}
