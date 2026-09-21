package com.mugsun.boot.datascope.rule;

import com.mugsun.boot.common.constant.DataScopeConstants;
import com.mugsun.boot.datascope.DataPermissionRule;
import com.mugsun.boot.datascope.DataScopeContext;
import com.mugsun.boot.datascope.DataScopeTables;
import com.mybatisflex.core.query.QueryColumn;
import com.mybatisflex.core.query.QueryCondition;
import org.springframework.stereotype.Component;

import java.util.List;

/** 自定义部门：部门列 IN 角色配置的自定义部门集合（sys_role_dept；空集 = -1 不可见，防穿透） */
@Component
public class CustomDeptDataRule implements DataPermissionRule {

	@Override
	public int scope() {
		return DataScopeConstants.CUSTOM_DEPT;
	}

	@Override
	public QueryCondition build(DataScopeContext.Ctx ctx, DataScopeTables.Columns columns) {
		List<Long> ids = ctx.customDeptIds() == null || ctx.customDeptIds().isEmpty()
			? List.of(-1L) : ctx.customDeptIds();
		return new QueryColumn(columns.deptColumn()).in(ids);
	}
}
