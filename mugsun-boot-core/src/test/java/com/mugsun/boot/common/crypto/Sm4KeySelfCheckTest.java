package com.mugsun.boot.common.crypto;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.mock.env.MockEnvironment;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class Sm4KeySelfCheckTest {

	private static final Map<String, List<String>> ONE = Map.of("sys_user", List.of("id_card"));

	private static DataSource ds(Connection conn) throws Exception {
		DataSource ds = mock(DataSource.class);
		when(ds.getConnection()).thenReturn(conn);
		DatabaseMetaData md = mock(DatabaseMetaData.class);
		when(conn.getMetaData()).thenReturn(md);
		when(md.getColumns(any(), any(), anyString(), anyString())).thenAnswer(i -> {
			ResultSet r = mock(ResultSet.class);
			when(r.next()).thenReturn(true);
			return r;
		});
		return ds;
	}

	@Test
	@DisplayName("每个字段只抽样：SQL 带 LIMIT 3，JDBC 也限 3 行")
	void samplesWithLimit() throws Exception {
		Connection conn = mock(Connection.class);
		DataSource ds = ds(conn);
		PreparedStatement ps = mock(PreparedStatement.class);
		ResultSet rs = mock(ResultSet.class);
		when(rs.next()).thenReturn(false);
		when(ps.executeQuery()).thenReturn(rs);
		when(conn.prepareStatement(anyString())).thenReturn(ps);

		Sm4KeySelfCheck.check(ds, ONE);

		verify(conn).prepareStatement(
			"SELECT id, id_card FROM sys_user WHERE id_card IS NOT NULL AND id_card <> '' LIMIT 3");
		verify(ps).setMaxRows(3);
		assertThat(Sm4KeySelfCheck.SAMPLE).isEqualTo(3);
	}

	@Test
	@DisplayName("空表、没有密文、字段不存在：直接放行")
	void emptyPasses() throws Exception {
		Connection conn = mock(Connection.class);
		DataSource ds = ds(conn);
		PreparedStatement ps = mock(PreparedStatement.class);
		ResultSet rs = mock(ResultSet.class);
		when(rs.next()).thenReturn(false);
		when(ps.executeQuery()).thenReturn(rs);
		when(conn.prepareStatement(anyString())).thenReturn(ps);
		assertThat(Sm4KeySelfCheck.check(ds, ONE)).isZero();

		Connection noCol = mock(Connection.class);
		DataSource ds2 = mock(DataSource.class);
		when(ds2.getConnection()).thenReturn(noCol);
		DatabaseMetaData md = mock(DatabaseMetaData.class);
		when(noCol.getMetaData()).thenReturn(md);
		when(md.getColumns(any(), any(), anyString(), anyString())).thenAnswer(i -> mock(ResultSet.class));
		assertThat(Sm4KeySelfCheck.check(ds2, ONE)).isZero();
		verify(noCol, never()).prepareStatement(anyString());
	}

	@ParameterizedTest(name = "active=[{0}] self-check=false -> skip={1}")
	@DisplayName("跳过开关只在非生产生效，生产判定同 DeployProfiles")
	@CsvSource(delimiter = '|', value = {
		"''|false",
		"prod|false",
		"production|false",
		"staging,dev|false",
		"prd,test|false",
		"demo|false",
		"dev|true",
		"test|true",
		"' Local '|true",
	})
	void skipOnlyOutsideProduction(String active, boolean expected) {
		MockEnvironment env = new MockEnvironment().withProperty(Sm4KeySelfCheck.SKIP_PROPERTY, "false");
		env.setActiveProfiles(active.isEmpty() ? new String[0] : active.split(","));
		assertThat(Sm4KeySelfCheck.skip(env)).isEqualTo(expected);
	}

	@Test
	@DisplayName("开关没关时任何环境都自检")
	void enabledByDefault() {
		MockEnvironment env = new MockEnvironment();
		env.setActiveProfiles("dev");
		assertThat(Sm4KeySelfCheck.skip(env)).isFalse();
	}

	@Test
	@DisplayName("生产即使配了 false，afterSingletonsInstantiated 也照常读库")
	void productionIgnoresSwitch() throws Exception {
		Connection conn = mock(Connection.class);
		DataSource ds = ds(conn);
		PreparedStatement ps = mock(PreparedStatement.class);
		ResultSet rs = mock(ResultSet.class);
		when(ps.executeQuery()).thenReturn(rs);
		when(conn.prepareStatement(anyString())).thenReturn(ps);
		org.springframework.beans.factory.ObjectProvider<DataSource> p = mock();
		when(p.getIfAvailable()).thenReturn(ds);
		MockEnvironment env = new MockEnvironment().withProperty(Sm4KeySelfCheck.SKIP_PROPERTY, "false");
		env.setActiveProfiles("prod");
		new Sm4KeySelfCheck(p, env).afterSingletonsInstantiated();
		verify(ds).getConnection();

		DataSource dsDev = mock(DataSource.class);
		when(p.getIfAvailable()).thenReturn(dsDev);
		env.setActiveProfiles("dev");
		new Sm4KeySelfCheck(p, env).afterSingletonsInstantiated();
		verify(dsDev, never()).getConnection();
	}
}
