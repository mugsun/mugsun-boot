package com.mugsun.boot.ai;

import com.mugsun.boot.AbstractIntegrationTest;
import com.mugsun.boot.ai.entity.AiDatasource;
import com.mugsun.boot.ai.service.AiDatasourceBizService;
import com.mugsun.boot.ai.support.SqlQueryRunner;
import com.mugsun.boot.ai.support.SqlReadOnlyChecker;
import com.mugsun.core.tool.exception.ServiceException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

/**
 * BOOT-074：问数和数据库节点的查询在真实 PostgreSQL 上跑一遍——只读事务、时限、结果大小、参数绑定。
 * 这几样靠的是数据库和驱动的实际行为，模拟对象测不出来。
 */
class AiSqlQueryApiTest extends AbstractIntegrationTest {

	private static final String TABLE = "wdx074_it_orders";

	@Autowired
	private AiDatasourceBizService datasources;

	@BeforeEach
	void createTable() {
		jdbc.execute("drop table if exists " + TABLE);
		jdbc.execute("create table " + TABLE + " (id bigint primary key, region varchar(32), amount numeric(12,2), "
			+ "paid boolean, created date)");
		jdbc.update("insert into " + TABLE + " (id, region, amount, paid, created) values "
			+ "(1, '华南', 137.00, true, date '2026-01-05'), "
			+ "(2, '华北', 80.50, false, date '2026-02-10'), "
			+ "(3, 'O''Brien', 1200.00, true, date '2026-03-15')");
	}

	@AfterEach
	void dropTable() {
		jdbc.execute("drop table if exists " + TABLE);
	}

	private static AiDatasource datasource() {
		AiDatasource row = new AiDatasource();
		row.setName("wdx074-it");
		row.setDbType("postgresql");
		row.setDriverClass("org.postgresql.Driver");
		row.setJdbcUrl(primaryJdbcUrl());
		row.setUsername(primaryJdbcUsername());
		row.setPassword(primaryJdbcPassword());
		return row;
	}

	private List<Map<String, Object>> query(String sql, int maxRows, int timeoutMs) {
		return datasources.runQuery(datasource(), sql, List.of(), maxRows, timeoutMs);
	}

	/** 和数据库节点走同一条路：检查模板、把变量换成参数、执行 */
	private List<Map<String, Object>> template(String template, Map<String, Object> values) {
		SqlReadOnlyChecker.Bound bound = SqlReadOnlyChecker.checkTemplate(template, Set.of(TABLE)).bind(values);
		return datasources.runQuery(datasource(), bound.sql(), bound.params(), 100, 5000);
	}

	private List<Object> ids(String where, Map<String, Object> values) {
		return template("select id from " + TABLE + " where " + where, values).stream()
			.map(row -> row.get("id")).toList();
	}

	@Test
	@DisplayName("查询跑在只读事务里，数据库自己也设了时限")
	void runsInReadOnlyTransactionWithServerSideTimeout() {
		Map<String, Object> row = query("select current_setting('transaction_read_only') as ro, "
			+ "current_setting('statement_timeout') as t", 10, 5000).get(0);
		assertThat(row.get("ro")).isEqualTo("on");
		assertThat(row.get("t")).isEqualTo("7s");
	}

	@Test
	@DisplayName("写操作就算过了检查，数据库也拒绝，数据不变")
	void databaseRefusesWrites() {
		String write = "with changed as (update " + TABLE + " set amount = 0.01 where id = 1 returning id) "
			+ "select count(*) as n from changed";
		assertThatThrownBy(() -> query(write, 10, 5000))
			.isInstanceOf(ServiceException.class).hasMessage(AiConstants.MSG_SQL_UNSAFE);
		assertThat(jdbc.queryForObject("select amount from " + TABLE + " where id = 1", BigDecimal.class))
			.isEqualByComparingTo("137.00");
	}

	@Test
	@DisplayName("慢查询到时限就停，数据库里也不再跑")
	void slowQueryIsStopped() {
		long start = System.nanoTime();
		assertThatThrownBy(() -> query("select pg_sleep(30) as wdx074_it", 10, 1000))
			.isInstanceOf(ServiceException.class).hasMessage(SqlQueryRunner.timeoutMessage(1));
		assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(Duration.ofSeconds(10));
		assertThat(jdbc.queryForObject("select count(*) from pg_stat_activity "
			+ "where state = 'active' and query like 'select pg_sleep(30) as wdx074_it%'", Integer.class)).isZero();
	}

	@Test
	@DisplayName("结果太大时停下来，不把应用内存占满")
	void oversizedResultIsStopped() {
		assertTimeoutPreemptively(Duration.ofSeconds(90), () -> {
			// 一个值就超了
			assertThatThrownBy(() -> query("select repeat('x', 40000000) as big", 10, 20000))
				.isInstanceOf(ServiceException.class).hasMessage(SqlQueryRunner.MSG_TOO_BIG);
			// 每行不大，加起来超了
			assertThatThrownBy(() -> query("select repeat('x', 100000) as big from generate_series(1, 2000)", 5000, 20000))
				.isInstanceOf(ServiceException.class).hasMessage(SqlQueryRunner.MSG_TOO_BIG);
			assertThat(query("select repeat('x', 1000000) as big", 10, 20000)).hasSize(1);
		});
	}

	@Test
	@DisplayName("变量按参数绑定：带引号的攻击串只是一个普通的值，各种类型按列的类型比较")
	void variablesAreBoundAsParameters() {
		String attack = "x' union all select id from sys_user --";
		assertThat(ids("region = '${region}' order by id", Map.of("region", attack))).isEmpty();
		assertThat(ids("region = '${region}'", Map.of("region", "O'Brien"))).containsExactly(3L);
		assertThat(ids("region = ${region}", Map.of("region", "华南"))).containsExactly(1L);
		// 表单里填的数字是文字，数据库按列的类型比较
		assertThat(ids("amount > ${min} order by id limit ${n}", Map.of("min", "100", "n", 1))).containsExactly(1L);
		assertThat(ids("amount = ${amount}", Map.of("amount", 80.5))).containsExactly(2L);
		assertThat(ids("id = ${id}", Map.of("id", "2"))).containsExactly(2L);
		assertThat(ids("id in (${ids}) order by id", Map.of("ids", List.of(1, "3")))).containsExactly(1L, 3L);
		assertThat(ids("paid = ${paid} order by id", Map.of("paid", true))).containsExactly(1L, 3L);
		assertThat(ids("created >= '${from}' order by id", Map.of("from", "2026-02-01"))).containsExactly(2L, 3L);
		assertThat(ids("region like '%${kw}%' order by id", Map.of("kw", "华"))).containsExactly(1L, 2L);
		Map<String, Object> empty = new HashMap<>();
		empty.put("region", null);
		assertThat(ids("region = coalesce(${region}, '华北')", empty)).containsExactly(2L);
		// 值原样回来，引号没有被当成 SQL
		assertThat(template("select '${label}' as label from " + TABLE + " where id = 1", Map.of("label", "a'b\"c"))
			.get(0).get("label")).isEqualTo("a'b\"c");
	}

	@Test
	@DisplayName("查不出来时只说固定的话，不带数据库的报错原文")
	void failuresUseFixedMessages() {
		assertThatThrownBy(() -> query("select no_such_column from " + TABLE, 10, 5000))
			.isInstanceOf(ServiceException.class).hasMessage(SqlQueryRunner.MSG_FAILED);
		AiDatasource unreachable = datasource();
		unreachable.setJdbcUrl("jdbc:postgresql://127.0.0.1:1/none");
		assertThatThrownBy(() -> datasources.runQuery(unreachable, "select 1", List.of(), 10, 5000))
			.isInstanceOf(ServiceException.class).hasMessage(SqlQueryRunner.MSG_CONNECT);
	}

	@Test
	@DisplayName("行数按上限截断")
	void rowsAreCapped() {
		assertThat(query("select g as n from generate_series(1, 50) g", 7, 5000)).hasSize(7);
	}
}
