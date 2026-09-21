package com.mugsun.boot.common.constant;

/**
 * 数据权限范围常量。
 */
public interface DataScopeConstants {

	/** 全部数据 */
	int ALL = 1;
	/** 本部门 */
	int DEPT = 2;
	/** 本部门及子部门 */
	int DEPT_AND_CHILD = 3;
	/** 仅本人 */
	int SELF = 4;
	/** 自定义部门（sys_role_dept） */
	int CUSTOM_DEPT = 5;
	/** 自定义 SQL（sys_role.custom_sql） */
	int CUSTOM_SQL = 6;
}
