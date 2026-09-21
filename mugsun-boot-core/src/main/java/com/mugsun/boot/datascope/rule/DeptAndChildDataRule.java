package com.mugsun.boot.datascope.rule;

import com.mugsun.boot.common.constant.DataScopeConstants;
import com.mugsun.boot.datascope.DataPermissionRule;
import com.mugsun.boot.datascope.DataScopeContext;
import com.mugsun.boot.datascope.DataScopeTables;
import com.mybatisflex.core.query.QueryColumn;
import com.mybatisflex.core.query.QueryCondition;
import org.springframework.stereotype.Component;

import java.util.List;

/** 本部门及子部门：部门列 IN 预解析的子树集合（空集 = -1 不可见，防穿透） */
@Component
public class DeptAndChildDataRule implements DataPermissionRule {

	@Override
	public int scope() {
		return DataScopeConstants.DEPT_AND_CHILD;
	}

	@Override
	public QueryCondition build(DataScopeContext.Ctx ctx, DataScopeTables.Columns columns) {
		List<Long> ids = ctx.subtreeDeptIds() == null || ctx.subtreeDeptIds().isEmpty()
			? List.of(-1L) : ctx.subtreeDeptIds();
		return new QueryColumn(columns.deptColumn()).in(ids);
	}
}
