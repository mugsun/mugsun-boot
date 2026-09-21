package com.mugsun.boot.datascope.rule;

import com.mugsun.boot.common.constant.DataScopeConstants;
import com.mugsun.boot.datascope.DataPermissionRule;
import com.mugsun.boot.datascope.DataScopeContext;
import com.mugsun.boot.datascope.DataScopeTables;
import com.mybatisflex.core.query.QueryColumn;
import com.mybatisflex.core.query.QueryCondition;
import org.springframework.stereotype.Component;

/** 本部门：部门列 = 当前用户部门（部门为空时 = -1 不可见，防穿透） */
@Component
public class DeptDataRule implements DataPermissionRule {

	@Override
	public int scope() {
		return DataScopeConstants.DEPT;
	}

	@Override
	public QueryCondition build(DataScopeContext.Ctx ctx, DataScopeTables.Columns columns) {
		return new QueryColumn(columns.deptColumn()).eq(ctx.deptId() == null ? -1L : ctx.deptId());
	}
}
