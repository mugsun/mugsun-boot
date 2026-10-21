package com.mugsun.boot.common.crypto;

import cn.hutool.crypto.SmUtil;
import com.mugsun.boot.AbstractIntegrationTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 离线迁移工具：dry-run 不写库、apply 后新密钥能解、补零短密钥的旧数据能还原、有解不开的整体回滚。
 * 新密钥用集成测试应用自己的密钥，测完把 admin 的 id_card 复原。
 */
class Sm4KeyMigrationToolTest extends AbstractIntegrationTest {

	private static final String APP_KEY = "6d756773756e2d746573742d736d346b";

	@Test
	@DisplayName("补零短密钥 → 新密钥：dry-run 只计数，apply 后可解；有解不开时整体回滚")
	void migratesLegacyPaddedData() throws Exception {
		byte[] oldRaw = Sm4Util.legacyPaddedKey("old-short");
		String plain = "110101199001011234";
		try (Connection c = DriverManager.getConnection(primaryJdbcUrl(), primaryJdbcUsername(), primaryJdbcPassword())) {
			String original = idCard(c);
			try {
				setIdCard(c, SmUtil.sm4(oldRaw).encryptBase64(plain));
				Sm4KeyMigrationTool tool = Sm4KeyMigrationTool.fromEnv(Map.of(
					"MUGSUN_SM4_OLD_KEY", "old-short",
					"MUGSUN_SM4_OLD_KEY_LEGACY_PADDED", "true",
					"MUGSUN_SM4_NEW_KEY", APP_KEY)::get);

				String before = idCard(c);
				var dry = tool.run(c, false);
				assertThat(dry.get("sys_user.id_card").reencrypted).isGreaterThanOrEqualTo(1);
				assertThat(dry.get("sys_user.id_card").failed).isZero();
				assertThat(idCard(c)).isEqualTo(before);

				var applied = tool.run(c, true);
				assertThat(applied.values()).allSatisfy(n -> assertThat(n.failed).isZero());
				assertThat(Sm4KeyMigrationTool.tryDecrypt(SmUtil.sm4(Sm4Util.parseKey(APP_KEY)), idCard(c))).isEqualTo(plain);

				// 第三把密钥加密的值：解不开 → apply 整体回滚
				setIdCard(c, SmUtil.sm4(Sm4Util.parseKey("ffeeddccbbaa99887766554433221100")).encryptBase64(plain));
				String broken = idCard(c);
				var failed = tool.run(c, true);
				assertThat(failed.get("sys_user.id_card").failed).isEqualTo(1);
				String report = failed.get("sys_user.id_card").toString();
				assertThat(report).contains("解不开的主键=").doesNotContain(broken).doesNotContain(plain);
				assertThat(idCard(c)).isEqualTo(broken);
			} finally {
				setIdCard(c, original);
			}
		}
	}

	@Test
	@DisplayName("新旧密钥相同、缺环境变量、新密钥格式不对：直接报错")
	void rejectsBadInput() {
		assertThatThrownBy(() -> Sm4KeyMigrationTool.fromEnv(Map.of("MUGSUN_SM4_OLD_KEY", APP_KEY,
			"MUGSUN_SM4_NEW_KEY", APP_KEY)::get)).isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> Sm4KeyMigrationTool.fromEnv(Map.of("MUGSUN_SM4_OLD_KEY", APP_KEY)::get))
			.hasMessageContaining("MUGSUN_SM4_NEW_KEY");
		assertThatThrownBy(() -> Sm4KeyMigrationTool.fromEnv(Map.of("MUGSUN_SM4_OLD_KEY", APP_KEY,
			"MUGSUN_SM4_NEW_KEY", "short")::get)).isInstanceOf(IllegalStateException.class);
	}

	@Test
	@DisplayName("新旧密钥相同（含同一把钥的 hex 与 base64 两种写法）：拒绝执行")
	void sameKeyRejected() {
		String b64 = java.util.Base64.getEncoder().encodeToString(Sm4Util.parseKey(APP_KEY));
		assertThatThrownBy(() -> Sm4KeyMigrationTool.fromEnv(Map.of("MUGSUN_SM4_OLD_KEY", APP_KEY,
			"MUGSUN_SM4_NEW_KEY", b64)::get)).isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("相同").hasMessageNotContaining(APP_KEY).hasMessageNotContaining(b64);
	}

	@Test
	@DisplayName("命令行只认 --dry-run / --apply，传 key 之类的参数直接拒绝，报错不回显参数")
	void rejectsCommandLineKeys() {
		assertThatThrownBy(() -> Sm4KeyMigrationTool.run(new String[]{"--old-key=" + APP_KEY}, Map.<String, String>of()::get))
			.isInstanceOf(IllegalArgumentException.class).hasMessageNotContaining(APP_KEY);
	}

	@Test
	@DisplayName("字段清单正好 15 个")
	void fifteenFields() {
		assertThat(Sm4KeyMigrationTool.FIELDS.values().stream().mapToInt(java.util.List::size).sum()).isEqualTo(15);
	}

	private static String idCard(Connection c) throws Exception {
		try (PreparedStatement ps = c.prepareStatement("SELECT id_card FROM sys_user WHERE username = ? AND tenant_id = ?")) {
			ps.setString(1, ADMIN_USERNAME);
			ps.setString(2, PLATFORM_TENANT);
			try (ResultSet rs = ps.executeQuery()) {
				rs.next();
				return rs.getString(1);
			}
		}
	}

	private static void setIdCard(Connection c, String v) throws Exception {
		try (PreparedStatement ps = c.prepareStatement("UPDATE sys_user SET id_card = ? WHERE username = ? AND tenant_id = ?")) {
			ps.setString(1, v);
			ps.setString(2, ADMIN_USERNAME);
			ps.setString(3, PLATFORM_TENANT);
			ps.executeUpdate();
		}
	}
}
