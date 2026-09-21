package com.mugsun.boot.system.excel;

import cn.idev.excel.annotation.ExcelProperty;

import java.time.LocalDateTime;

/**
 * 用户导出模型：展示导向（部门/角色为名串），列多于导入模型。
 * 手机号取数已经字段级权限裁决（无查看权为 null、无明文权为脱敏值），此处只做透传。
 */
public class SysUserExportExcel {

	@ExcelProperty("用户名")
	private String username;

	@ExcelProperty("昵称")
	private String nickname;

	@ExcelProperty("部门")
	private String deptName;

	@ExcelProperty("角色")
	private String roleNames;

	@ExcelProperty("邮箱")
	private String email;

	@ExcelProperty("手机")
	private String phone;

	@ExcelProperty("状态(1启用/0禁用)")
	private Integer status;

	@ExcelProperty("创建时间")
	private LocalDateTime createTime;

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

	public String getRoleNames() {
		return roleNames;
	}

	public void setRoleNames(String roleNames) {
		this.roleNames = roleNames;
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

	public LocalDateTime getCreateTime() {
		return createTime;
	}

	public void setCreateTime(LocalDateTime createTime) {
		this.createTime = createTime;
	}
}
