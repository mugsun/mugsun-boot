package com.mugsun.boot.common.crypto;

import cn.hutool.crypto.SmUtil;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.startsWith;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 不依赖 Docker 的单测：迁移工具的 dry-run / apply / 回滚 / 恢复 autoCommit 路径，以及启动自检。
 * 用 mock 的 JDBC 对象，每个加密字段都返回一行同一个值。
 */
class Sm4MigrationUnitTest {

	/** Sm4Util 的密钥是 JVM 级静态状态：用完恢复成集成测试密钥，避免后面复用缓存上下文的集成测试用错密钥写库 */
	@AfterEach
	void restoreIntegrationSm4Key() {
		Sm4TestKeys.useIntegrationKey();
	}

	private static final byte[] OLD = Sm4Util.legacyPaddedKey("old-short");
	private static final byte[] NEW = Sm4Util.parseKey("00112233445566778899aabbccddeeff");
	private static final byte[] OTHER = Sm4Util.parseKey("ffeeddccbbaa99887766554433221100");

	private Connection conn;
	private PreparedStatement update;

	@BeforeEach
	void setUp() {
		conn = mock(Connection.class);
		update = mock(PreparedStatement.class);
	}

	/** 每个字段都有一行（id=7，值=cipher）；selectFails=true 时 SELECT 抛 SQLException */
	private void stub(String cipher, boolean selectFails) throws SQLException {
		DatabaseMetaData md = mock(DatabaseMetaData.class);
		when(conn.getMetaData()).thenReturn(md);
		when(conn.getAutoCommit()).thenReturn(true);
		when(md.getColumns(any(), any(), anyString(), anyString())).thenAnswer(i -> {
			ResultSet r = mock(ResultSet.class);
			when(r.next()).thenReturn(true);
			return r;
		});
		when(conn.prepareStatement(startsWith("SELECT"))).thenAnswer(i -> {
			PreparedStatement ps = mock(PreparedStatement.class);
			if (selectFails) {
				when(ps.executeQuery()).thenThrow(new SQLException("boom", "08006"));
			} else {
				ResultSet rs = mock(ResultSet.class);
				when(rs.next()).thenReturn(true, false);
				when(rs.getObject(1)).thenReturn(7L);
				when(rs.getString(2)).thenReturn(cipher);
				when(ps.executeQuery()).thenReturn(rs);
			}
			return ps;
		});
		when(conn.prepareStatement(startsWith("UPDATE"))).thenReturn(update);
	}

	@Test
	@DisplayName("dry-run：只统计，不写库、不提交，最后回滚并恢复 autoCommit")
	void dryRunNeverCommits() throws Exception {
		stub(SmUtil.sm4(OLD.clone()).encryptBase64("110101199001011234"), false);
		var out = new Sm4KeyMigrationTool(OLD, NEW).run(conn, false);
		assertThat(out.get("sys_user.id_card").reencrypted).isEqualTo(1);
		verify(conn, never()).commit();
		verify(conn).rollback();
		verify(update, never()).executeBatch();
		verify(conn).setAutoCommit(true);
	}

	@Test
	@DisplayName("apply 全部可解：批量更新并提交")
	void applyCommits() throws Exception {
		stub(SmUtil.sm4(OLD.clone()).encryptBase64("secret"), false);
		new Sm4KeyMigrationTool(OLD, NEW).run(conn, true);
		verify(conn).commit();
		verify(conn, never()).rollback();
	}

	@Test
	@DisplayName("apply 有一条解不开：整体回滚，不提交；报告只有主键不含值")
	void applyWithFailureRollsBack() throws Exception {
		String cipher = SmUtil.sm4(OTHER.clone()).encryptBase64("secret");
		stub(cipher, false);
		var out = new Sm4KeyMigrationTool(OLD, NEW).run(conn, true);
		assertThat(out.get("sys_user.id_card").failedIds).containsExactly(7L);
		assertThat(out.get("sys_user.id_card").toString()).contains("主键=[7]").doesNotContain(cipher).doesNotContain("secret");
		verify(conn, never()).commit();
		verify(conn).rollback();
		verify(conn).setAutoCommit(true);
	}

	@Test
	@DisplayName("已是新钥加密的值（重跑）：算「已是新密钥」，不重加密")
	void alreadyNewNotReencrypted() throws Exception {
		byte[] same = OLD.clone();
		stub(SmUtil.sm4(NEW.clone()).encryptBase64("secret"), false);
		var out = new Sm4KeyMigrationTool(same, NEW).run(conn, false);
		assertThat(out.get("sys_user.id_card").alreadyNew).isEqualTo(1);
		assertThat(out.get("sys_user.id_card").reencrypted).isZero();
	}

	@Test
	@DisplayName("中途抛 SQLException：回滚并恢复 autoCommit，异常继续抛出")
	void sqlExceptionRollsBack() throws Exception {
		stub(null, true);
		assertThatThrownBy(() -> new Sm4KeyMigrationTool(OLD, NEW).run(conn, true)).isInstanceOf(SQLException.class);
		verify(conn, never()).commit();
		verify(conn).rollback();
		verify(conn).setAutoCommit(true);
	}

	@Test
	@DisplayName("--dry-run 与 --apply 同时给：参数冲突；未知参数：拒绝；都不回显参数")
	void argumentRules() {
		assertThatThrownBy(() -> Sm4KeyMigrationTool.run(new String[]{"--dry-run", "--apply"}, Map.<String, String>of()::get))
			.isInstanceOf(IllegalArgumentException.class).hasMessageContaining("冲突");
		assertThatThrownBy(() -> Sm4KeyMigrationTool.run(new String[]{"00112233445566778899aabbccddeeff"}, Map.<String, String>of()::get))
			.isInstanceOf(IllegalArgumentException.class).hasMessageNotContaining("00112233");
	}

	@Test
	@DisplayName("启动自检：当前钥能解放行；解不开拒绝启动且只报表、字段、主键；空库放行")
	void selfCheck() throws Exception {
		Sm4Util.init("00112233445566778899aabbccddeeff");
		DataSource ds = mock(DataSource.class);
		when(ds.getConnection()).thenReturn(conn);
		Map<String, List<String>> one = Map.of("sys_user", List.of("id_card"));

		stub(SmUtil.sm4(NEW.clone()).encryptBase64("110101199001011234"), false);
		assertThat(Sm4KeySelfCheck.check(ds, one)).isEqualTo(1);

		setUp();
		when(ds.getConnection()).thenReturn(conn);
		// 旧部署配 32 位 hex：旧版实际用前 16 个字符的 UTF-8 字节
		String legacy = SmUtil.sm4(Sm4Util.legacyPaddedKey("00112233445566778899aabbccddeeff")).encryptBase64("x");
		stub(legacy, false);
		assertThatThrownBy(() -> Sm4KeySelfCheck.check(ds, one)).isInstanceOf(IllegalStateException.class)
			.hasMessageContaining("sys_user.id_card").hasMessageContaining("主键 7")
			.hasMessageContaining("LEGACY_PADDED").hasMessageNotContaining(legacy);

		setUp();
		when(ds.getConnection()).thenReturn(conn);
		stub("plain-not-cipher", false);
		assertThat(Sm4KeySelfCheck.check(ds, one)).isZero();
	}
}
