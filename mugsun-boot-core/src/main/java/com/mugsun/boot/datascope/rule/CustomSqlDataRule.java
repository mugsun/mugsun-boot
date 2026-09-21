package com.mugsun.boot.datascope.rule;

import com.mugsun.boot.common.constant.DataScopeConstants;
import com.mugsun.boot.datascope.DataPermissionRule;
import com.mugsun.boot.datascope.DataScopeContext;
import com.mugsun.boot.datascope.DataScopeTables;
import com.mybatisflex.core.query.QueryCondition;
import com.mybatisflex.core.query.RawQueryCondition;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 自定义 SQL：按角色配置的 SQL 片段（sys_role.custom_sql）过滤，多片段 OR 合成。
 * <p>片段为平台管理员配置的受控表 WHERE 条件（同 G31 报表白名单，属可信配置）；无片段则不贡献（交引擎 fail-closed）。
 */
@Component
public class CustomSqlDataRule implements DataPermissionRule {

	@Override
	public int scope() {
		return DataScopeConstants.CUSTOM_SQL;
	}

	@Override
	public QueryCondition build(DataScopeContext.Ctx ctx, DataScopeTables.Columns columns) {
		List<String> sqls = ctx.customSqls();
		if (sqls == null || sqls.isEmpty()) {
			return null;
		}
		QueryCondition combined = null;
		for (String sql : sqls) {
			// 括号包裹每个片段：单片段亦生成 AND (sql)，把片段内顶层 OR 锁进括号，杜绝挣脱租户/逻辑删除条件的跨租户穿透
			QueryCondition cond = new RawQueryCondition("(" + sql + ")");
			combined = (combined == null) ? cond : combined.or(cond);
		}
		return combined;
	}
}
