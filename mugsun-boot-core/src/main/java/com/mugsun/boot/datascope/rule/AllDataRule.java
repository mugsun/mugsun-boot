package com.mugsun.boot.datascope.rule;

import com.mugsun.boot.common.constant.DataScopeConstants;
import com.mugsun.boot.datascope.DataPermissionRule;
import com.mugsun.boot.datascope.DataScopeContext;
import com.mugsun.boot.datascope.DataScopeTables;
import com.mybatisflex.core.query.QueryCondition;
import org.springframework.stereotype.Component;

/** 全部数据：不贡献条件（引擎特判「全部」为不注入） */
@Component
public class AllDataRule implements DataPermissionRule {

	@Override
	public int scope() {
		return DataScopeConstants.ALL;
	}

	@Override
	public QueryCondition build(DataScopeContext.Ctx ctx, DataScopeTables.Columns columns) {
		return null;
	}
}
