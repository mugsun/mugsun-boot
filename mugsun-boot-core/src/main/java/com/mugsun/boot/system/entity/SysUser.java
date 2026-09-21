package com.mugsun.boot.system.entity;

import com.mugsun.boot.common.constant.FieldMaskConstants;
import com.mugsun.boot.common.crypto.Sm4TypeHandler;
import com.mugsun.boot.log.AuditField;
import com.mugsun.core.mybatis.base.BaseEntity;
import com.mybatisflex.annotation.Column;
import com.mybatisflex.annotation.ColumnMask;
import com.mybatisflex.annotation.Table;

import java.time.LocalDate;

/**
 * 系统用户
 */
@Table("sys_user")
public class SysUser extends BaseEntity {

	private String username;
	private String password;
	@AuditField("昵称")
	private String nickname;
	/** 真实姓名（档案/主管选择展示） */
	@AuditField("真实姓名")
	private String realName;
	/** 性别：0 未知 / 1 男 / 2 女（字典 user_sex） */
	@AuditField(value = "性别", dict = "user_sex")
	private Integer sex;
	/** 生日 */
	private LocalDate birthday;
	/** 头像 URL（列自 V61；个人中心亦可直写） */
	private String avatar;
	/** 工号/用户编号 */
	@AuditField("工号")
	private String code;
	/** 直属主管用户 id */
	private Long leaderId;
	/** 是否主管：1 是 / 0 否（供 leader-list / 流程选人） */
	private Integer isLeader;
	@AuditField(value = "状态", dict = "user_status")
	private Integer status;
	private Long deptId;
	private Long postId;
	private String tenantId;
	/** 邮箱：通知邮件渠道联系方式 */
	private String email;
	/** 手机号：明文存储，按角色决策 明文/脱敏/不可见（@ColumnMask 自定义类型 + RoleAwareMaskProcessor） */
	@AuditField("手机号")
	@ColumnMask(FieldMaskConstants.TYPE_USER_PHONE)
	private String phone;
	/** 身份证号：SM4 加密存储（TypeHandler 入库加密、查询解密）+ 按角色决策 明文/脱敏/不可见（解密后叠加脱敏决策层） */
	@AuditField("身份证")
	@Column(typeHandler = Sm4TypeHandler.class)
	@ColumnMask(FieldMaskConstants.TYPE_USER_ID_CARD)
	private String idCard;

	/** 部门名（展示用，非表列，page/detail 富化填充） */
	@Column(ignore = true)
	private String deptName;
	/** 岗位名（展示用，非表列） */
	@Column(ignore = true)
	private String postName;
	/** 角色名串（展示用，非表列，顿号分隔） */
	@Column(ignore = true)
	private String roleNames;
	/** 直属主管展示名（非表列，page 富化） */
	@Column(ignore = true)
	private String leaderName;
	/** 角色 id 集合（建档/编辑挂角色入参 + detail 回显，非表列） */
	@Column(ignore = true)
	private java.util.List<Long> roleIds;

	public String getUsername() {
		return username;
	}

	public void setUsername(String username) {
		this.username = username;
	}

	public String getPassword() {
		return password;
	}

	public void setPassword(String password) {
		this.password = password;
	}

	public String getNickname() {
		return nickname;
	}

	public void setNickname(String nickname) {
		this.nickname = nickname;
	}

	public String getRealName() {
		return realName;
	}

	public void setRealName(String realName) {
		this.realName = realName;
	}

	public Integer getSex() {
		return sex;
	}

	public void setSex(Integer sex) {
		this.sex = sex;
	}

	public LocalDate getBirthday() {
		return birthday;
	}

	public void setBirthday(LocalDate birthday) {
		this.birthday = birthday;
	}

	public String getAvatar() {
		return avatar;
	}

	public void setAvatar(String avatar) {
		this.avatar = avatar;
	}

	public String getCode() {
		return code;
	}

	public void setCode(String code) {
		this.code = code;
	}

	public Long getLeaderId() {
		return leaderId;
	}

	public void setLeaderId(Long leaderId) {
		this.leaderId = leaderId;
	}

	public Integer getIsLeader() {
		return isLeader;
	}

	public void setIsLeader(Integer isLeader) {
		this.isLeader = isLeader;
	}

	public Integer getStatus() {
		return status;
	}

	public void setStatus(Integer status) {
		this.status = status;
	}

	public Long getDeptId() {
		return deptId;
	}

	public void setDeptId(Long deptId) {
		this.deptId = deptId;
	}

	public Long getPostId() {
		return postId;
	}

	public void setPostId(Long postId) {
		this.postId = postId;
	}

	public String getTenantId() {
		return tenantId;
	}

	public void setTenantId(String tenantId) {
		this.tenantId = tenantId;
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

	public String getIdCard() {
		return idCard;
	}

	public void setIdCard(String idCard) {
		this.idCard = idCard;
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

	public String getRoleNames() {
		return roleNames;
	}

	public void setRoleNames(String roleNames) {
		this.roleNames = roleNames;
	}

	public String getLeaderName() {
		return leaderName;
	}

	public void setLeaderName(String leaderName) {
		this.leaderName = leaderName;
	}

	public java.util.List<Long> getRoleIds() {
		return roleIds;
	}

	public void setRoleIds(java.util.List<Long> roleIds) {
		this.roleIds = roleIds;
	}
}
