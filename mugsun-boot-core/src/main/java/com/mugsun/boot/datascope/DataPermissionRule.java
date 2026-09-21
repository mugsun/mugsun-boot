package com.mugsun.boot.datascope;

import com.mybatisflex.core.query.QueryCondition;

/**
 * 数据权限规则（可插拔）：一种数据范围 = 一个规则 Bean。新增维度 = 加 Bean，不改任何 switch。
 * 规则只读<b>预解析</b>的 {@link DataScopeContext.Ctx} 纯拼 {@link QueryCondition}，绝不查库（在 SQL 生成期执行）。
 * 返回 {@code null} 表示本规则不贡献条件（如「全部」范围由引擎特判为不注入）。
 */
public interface DataPermissionRule {

	/** 适配的数据范围值（1全部/2本部门/3本部门及子/4仅本人/5自定义部门/6自定义SQL） */
	int scope();

	/**
	 * 构建该范围的过滤条件。
	 *
	 * @param ctx     预解析上下文
	 * @param columns 受控表的部门/本人列
	 * @return 过滤条件；{@code null} 表示不贡献
	 */
	QueryCondition build(DataScopeContext.Ctx ctx, DataScopeTables.Columns columns);
}
