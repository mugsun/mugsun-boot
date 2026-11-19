package com.mugsun.boot.ai.support;

import com.mugsun.boot.ai.AiConstants;
import com.mugsun.core.tool.exception.ServiceException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.SQLTimeoutException;
import java.sql.Statement;
import java.sql.Types;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class SqlQueryRunnerTest {

	private static final String SQL = "select region, total from wdx074_sales";

	private Connection connection;
	private Statement setup;
	private Statement statement;
	private PreparedStatement prepared;

	@BeforeEach
	void setUp() throws SQLException {
		connection = mock(Connection.class);
		setup = mock(Statement.class);
		statement = mock(Statement.class);
		prepared = mock(PreparedStatement.class);
		ResultSet rows = threeRows();
		when(statement.executeQuery(anyString())).thenReturn(rows);
		when(prepared.executeQuery()).thenReturn(rows);
		when(connection.prepareStatement(anyString())).thenReturn(prepared);
	}

	private static ResultSet threeRows() throws SQLException {
		ResultSet rows = mock(ResultSet.class);
		ResultSetMetaData meta = mock(ResultSetMetaData.class);
		when(meta.getColumnCount()).thenReturn(2);
		when(meta.getColumnLabel(1)).thenReturn("region");
		when(meta.getColumnLabel(2)).thenReturn("total");
		when(rows.getMetaData()).thenReturn(meta);
		when(rows.next()).thenReturn(true, true, true, false);
		when(rows.getObject(1)).thenReturn("华南", "华北", "华东");
		when(rows.getObject(2)).thenReturn(1, 2, 3);
		return rows;
	}

	private static SqlReadOnlyChecker.Param param(Object value, boolean quoted) {
		return new SqlReadOnlyChecker.Param(value, quoted);
	}

	@Test
	void runsInsideReadOnlyTransactionWithTimeoutAndRowLimit() throws SQLException {
		when(connection.createStatement()).thenReturn(setup, statement);

		List<Map<String, Object>> result = SqlQueryRunner.run(connection, SQL, List.of(), 2, 0, true);

		assertEquals(2, result.size());
		assertEquals(List.of("region", "total"), new ArrayList<>(result.get(0).keySet()));
		assertEquals("华南", result.get(0).get("region"));
		assertEquals(2, result.get(1).get("total"));
		InOrder order = inOrder(connection, setup, statement);
		order.verify(connection).setReadOnly(true);
		order.verify(connection).setAutoCommit(false);
		order.verify(setup).execute("set local statement_timeout = 32000");
		order.verify(statement).setEscapeProcessing(false);
		order.verify(statement).setQueryTimeout(30);
		order.verify(statement).setMaxRows(2);
		order.verify(statement).executeQuery(SQL);
		order.verify(connection).rollback();
		verify(connection, never()).commit();
		verify(connection, never()).prepareStatement(anyString());
	}

	@Test
	void otherDatabasesGetNoServerSideTimeoutStatement() throws SQLException {
		when(connection.createStatement()).thenReturn(statement);

		List<Map<String, Object>> result = SqlQueryRunner.run(connection, SQL, null, 0, 5000, false);

		assertEquals(1, result.size());
		verify(statement, never()).execute(anyString());
		verify(statement).setQueryTimeout(5);
		verify(statement).setMaxRows(1);
		verify(connection, times(1)).createStatement();
		verify(connection).setReadOnly(true);
		verify(connection).rollback();
	}

	@Test
	void postgresBindsValuesWithoutAType() throws SQLException {
		when(connection.createStatement()).thenReturn(setup);
		String attack = "华南' or '1'='1";

		SqlQueryRunner.run(connection, "select ?", List.of(param(attack, true), param(5L, false),
			param(new BigDecimal("1.50"), false), param(null, true), param(true, false)), 10, 0, true);

		verify(connection).prepareStatement("select ?");
		verify(prepared).setObject(1, attack, Types.OTHER);
		verify(prepared).setObject(2, "5", Types.OTHER);
		verify(prepared).setObject(3, "1.50", Types.OTHER);
		verify(prepared).setNull(4, Types.NULL);
		verify(prepared).setObject(5, "true", Types.OTHER);
		verify(prepared).setQueryTimeout(30);
		verify(prepared).setMaxRows(10);
		verify(prepared).executeQuery();
		verify(prepared, never()).setString(anyInt(), anyString());
		verify(setup).execute("set local statement_timeout = 32000");
		verify(connection).rollback();
	}

	@Test
	void otherDatabasesBindByJavaType() throws SQLException {
		SqlQueryRunner.run(connection, "select ?", List.of(param("华南", true), param("12", false), param("12", true),
			param("1.5", false), param(5L, false), param(new BigDecimal("2.50"), false), param(null, false),
			param(true, false), param("1e5", false), param("12345678901234567890", false)), 10, 0, false);

		verify(prepared).setString(1, "华南");
		verify(prepared).setLong(2, 12L);
		verify(prepared).setString(3, "12");
		verify(prepared).setBigDecimal(4, new BigDecimal("1.5"));
		verify(prepared).setLong(5, 5L);
		verify(prepared).setBigDecimal(6, new BigDecimal("2.50"));
		verify(prepared).setNull(7, Types.VARCHAR);
		verify(prepared).setBoolean(8, true);
		verify(prepared).setString(9, "1e5");
		verify(prepared).setString(10, "12345678901234567890");
		verify(prepared, never()).setObject(anyInt(), any(), anyInt());
		verify(connection, never()).createStatement();
	}

	@Test
	void fallsBackToTypedBindingWhenTheDriverRefusesUntypedValues() throws SQLException {
		when(connection.createStatement()).thenReturn(setup);
		doThrow(new SQLException("not supported")).when(prepared).setObject(anyInt(), any(), eq(Types.OTHER));

		SqlQueryRunner.run(connection, "select ?", List.of(param("华南", true), param(7L, false)), 10, 0, true);

		verify(prepared).setString(1, "华南");
		verify(prepared).setLong(2, 7L);
	}

	@Test
	void databaseErrorsBecomeFixedMessages() throws SQLException {
		assertEquals(SqlQueryRunner.timeoutMessage(5), failWith(new SQLTimeoutException("Statement cancelled"), 5000));
		assertEquals(SqlQueryRunner.timeoutMessage(30),
			failWith(new SQLException("canceling statement due to statement timeout", "57014"), 0));
		assertEquals(AiConstants.MSG_SQL_UNSAFE,
			failWith(new SQLException("cannot execute UPDATE in a read-only transaction", "25006"), 0));
		assertEquals(SqlQueryRunner.MSG_TOO_BIG, failWith(new SQLException(
			"Result set exceeded maxResultBuffer limit. Received:  33554433; Current limit: 33554432", "08S01"), 0));
		assertEquals(SqlQueryRunner.MSG_CONNECT, failWith(new SQLException("connection reset", "08006"), 0));
		assertEquals(SqlQueryRunner.MSG_FAILED,
			failWith(new SQLException("relation \"sys_user\" does not exist", "42P01"), 0));
		assertEquals(SqlQueryRunner.MSG_FAILED, failWith(new SQLException((String) null), 0));
	}

	private static String failWith(SQLException error, int timeoutMs) throws SQLException {
		Connection c = mock(Connection.class);
		Statement st = mock(Statement.class);
		when(c.createStatement()).thenReturn(st);
		when(st.executeQuery(anyString())).thenThrow(error);
		ServiceException thrown = assertThrows(ServiceException.class,
			() -> SqlQueryRunner.run(c, SQL, List.of(), 10, timeoutMs, false));
		verify(c).rollback();
		return thrown.getMessage();
	}

	@Test
	void stillRunsWhenTheDriverCannotOpenAReadOnlyTransaction() throws SQLException {
		doThrow(new SQLException("not supported")).when(connection).setReadOnly(true);
		doThrow(new SQLException("not supported")).when(connection).setAutoCommit(false);
		when(connection.createStatement()).thenReturn(statement);
		doThrow(new SQLException("not supported")).when(statement).setQueryTimeout(anyInt());

		List<Map<String, Object>> result = SqlQueryRunner.run(connection, SQL, List.of(), 10, 0, true);

		assertEquals(3, result.size());
		verify(statement, never()).execute(anyString());
		verify(statement).setMaxRows(10);
		verify(connection, never()).rollback();
	}

	@Test
	void keepsGoingWhenTheServerSideTimeoutIsRefused() throws SQLException {
		when(connection.createStatement()).thenReturn(setup, statement);
		when(setup.execute(anyString())).thenThrow(new SQLException("unrecognized configuration parameter", "42704"));

		assertEquals(3, SqlQueryRunner.run(connection, SQL, List.of(), 10, 0, true).size());

		verify(connection, times(2)).rollback();
		verify(statement).setQueryTimeout(30);
	}

	@Test
	void timeoutIsClampedToSeconds() {
		assertEquals(30, SqlQueryRunner.timeoutSeconds(0));
		assertEquals(30, SqlQueryRunner.timeoutSeconds(-5));
		assertEquals(1, SqlQueryRunner.timeoutSeconds(1));
		assertEquals(1, SqlQueryRunner.timeoutSeconds(1000));
		assertEquals(2, SqlQueryRunner.timeoutSeconds(1001));
		assertEquals(30, SqlQueryRunner.timeoutSeconds(30_000));
		assertEquals(120, SqlQueryRunner.timeoutSeconds(500_000));
		assertEquals(120, SqlQueryRunner.timeoutSeconds(Integer.MAX_VALUE));
		assertEquals("查询超过 30 秒，已停止。请缩小查询范围后再试", SqlQueryRunner.timeoutMessage(30));
	}
}
