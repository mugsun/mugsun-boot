package com.mugsun.boot.datascope;

import com.mugsun.boot.common.constant.DataScopeConstants;
import com.mybatisflex.core.query.QueryCondition;
import com.mybatisflex.core.query.QueryWrapper;
import com.mybatisflex.core.query.RawQueryCondition;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 数据权限引擎：对受控表按当前用户<b>全部角色数据范围求 OR 并集</b>注入 QueryWrapper。
 * 由 {@link MugsunDataScopeDialect} 在 SQL 生成期调用（纯拼条件、不查库）。
 * <p>fail-closed：生效于受控表但解析不出任何范围条件时注入 {@code 1 = 0}（拒绝，绝不返全量）。
 * 任一角色为「全部」范围则不注入（不限）。
 */
@Component
public class DataScopeEngine {

	private final Map<Integer, DataPermissionRule> rules;

	public DataScopeEngine(List<DataPermissionRule> ruleList) {
		this.rules = ruleList.stream().collect(Collectors.toMap(DataPermissionRule::scope, r -> r));
	}

	/** 对查询的某张表按数据权限注入条件（仅受控表 + 生效状态才处理） */
	public void apply(QueryWrapper query, String tableName) {
		if (!DataScopeContext.isActive()) {
			return;
		}
		DataScopeTables.Columns cols = DataScopeTables.of(tableName);
		if (cols == null) {
			return;
		}
		DataScopeContext.Ctx ctx = DataScopeContext.current();
		if (ctx == null || ctx.scopes() == null || ctx.scopes().isEmpty()) {
			// 受控表 + 生效 + 无任何范围 → fail-closed
			query.and(new RawQueryCondition("1 = 0"));
			return;
		}
		if (ctx.scopes().contains(DataScopeConstants.ALL)) {
			// 任一角色「全部」→ OR 并集即全部，不注入
			return;
		}
		QueryCondition combined = null;
		for (Integer scope : ctx.scopes()) {
			DataPermissionRule rule = rules.get(scope);
			if (rule == null) {
				continue;
			}
			QueryCondition cond = rule.build(ctx, cols);
			if (cond == null) {
				continue;
			}
			// QueryCondition.or 自动加 Brackets 分组，qw.and(chain) 生成 AND (c1 OR c2 ...)，OR 优先级安全
			combined = (combined == null) ? cond : combined.or(cond);
		}
		if (combined == null) {
			query.and(new RawQueryCondition("1 = 0"));
		} else {
			query.and(combined);
		}
	}
}
