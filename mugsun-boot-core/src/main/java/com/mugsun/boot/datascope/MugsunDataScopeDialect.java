package com.mugsun.boot.datascope;

import com.mugsun.boot.gen.SqlDialect;
import com.mybatisflex.core.dialect.DbType;
import com.mybatisflex.core.dialect.KeywordWrap;
import com.mybatisflex.core.dialect.LimitOffsetProcessor;
import com.mybatisflex.core.dialect.OperateType;
import com.mybatisflex.core.dialect.impl.CommonsDialectImpl;
import com.mybatisflex.core.query.CPI;
import com.mybatisflex.core.query.QueryTable;
import com.mybatisflex.core.query.QueryWrapper;

import java.util.List;

/**
 * 数据权限方言：覆写 Flex 官方零侵入行级授权钩子 {@code prepareAuth}，在 SQL 生成期对受控表自动注入数据权限条件。
 * <p>按 {@link DbType} 选标识符引号与分页处理器：PG 系双引号+LIMIT；Oracle/达梦无引号+OFFSET FETCH；
 * MySQL 系反引号+LIMIT。SELECT 覆盖读隔离（分页 count 与 data 同过此钩子）；仅 {@link DataScopeContext#isActive()} 时注入。
 */
public class MugsunDataScopeDialect extends CommonsDialectImpl {

	private final DataScopeEngine engine;

	public MugsunDataScopeDialect(DataScopeEngine engine) {
		this(engine, KeywordWrap.DOUBLE_QUOTATION, LimitOffsetProcessor.POSTGRESQL);
	}

	public MugsunDataScopeDialect(DataScopeEngine engine, KeywordWrap wrap, LimitOffsetProcessor limit) {
		super(wrap, limit);
		this.engine = engine;
	}

	/** 按 DbType 归族构造：与 {@link SqlDialect#of(DbType)} 一致 */
	public static MugsunDataScopeDialect create(DbType dbType, DataScopeEngine engine) {
		SqlDialect family = SqlDialect.of(dbType);
		return switch (family) {
			case ORACLE ->
				// 达梦/Oracle：未加引号标识符按大写；分页用 SQL:2008 OFFSET FETCH（Oracle 12c+/达梦 Oracle 模式）
				new MugsunDataScopeDialect(engine, KeywordWrap.NONE, LimitOffsetProcessor.DERBY);
			case MYSQL ->
				new MugsunDataScopeDialect(engine, KeywordWrap.BACK_QUOTE, LimitOffsetProcessor.MYSQL);
			default ->
				new MugsunDataScopeDialect(engine, KeywordWrap.DOUBLE_QUOTATION, LimitOffsetProcessor.POSTGRESQL);
		};
	}

	@Override
	public void prepareAuth(QueryWrapper queryWrapper, OperateType operateType) {
		if (operateType != OperateType.SELECT) {
			return;
		}
		List<QueryTable> tables = CPI.getQueryTables(queryWrapper);
		if (tables == null || tables.isEmpty()) {
			return;
		}
		for (QueryTable table : tables) {
			engine.apply(queryWrapper, table.getName());
		}
	}
}
