package com.mugsun.boot.datascope.rule;

import com.mugsun.boot.common.constant.DataScopeConstants;
import com.mugsun.boot.datascope.DataPermissionRule;
import com.mugsun.boot.datascope.DataScopeContext;
import com.mugsun.boot.datascope.DataScopeTables;
import com.mybatisflex.core.query.QueryColumn;
import com.mybatisflex.core.query.QueryCondition;
import org.springframework.stereotype.Component;

/** 仅本人：本人列 = 当前用户（用户为空时 = -1 不可见） */
@Component
public class SelfDataRule implements DataPermissionRule {

	@Override
	public int scope() {
		return DataScopeConstants.SELF;
	}

	@Override
	public QueryCondition build(DataScopeContext.Ctx ctx, DataScopeTables.Columns columns) {
		return new QueryColumn(columns.userColumn()).eq(ctx.userId() == null ? -1L : ctx.userId());
	}
}
