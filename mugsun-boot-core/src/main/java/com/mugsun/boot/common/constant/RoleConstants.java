package com.mugsun.boot.common.constant;

/**
 * 角色常量。
 */
public interface RoleConstants {

	/** 内置管理员角色编码：通配全部权限、可切换租户视图 */
	String ADMIN = "admin";

	/** 内置普通用户角色编码：自助注册默认角色，仅工作台/个人域，无任何管理权限码 */
	String USER = "user";

	/** 前端菜单门控伪角色（全量权限码 RBAC 落地前的兜底可见性标识） */
	String FRONT_SUPER = "R_SUPER";
	String FRONT_ADMIN = "R_ADMIN";
}
