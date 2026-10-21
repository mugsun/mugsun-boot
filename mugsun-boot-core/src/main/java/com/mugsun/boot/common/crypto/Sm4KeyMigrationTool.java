package com.mugsun.boot.common.crypto;

import cn.hutool.crypto.SmUtil;
import cn.hutool.crypto.symmetric.SM4;

import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * SM4 字段加密换密钥的离线迁移工具：应用停机后单独运行，把 15 个加密字段从旧密钥重新加密到新密钥。
 * <p>密钥和数据库口令只从环境变量读，不走命令行参数（避免进 shell 历史和 ps）：
 * <ul>
 *   <li>{@code MUGSUN_SM4_OLD_KEY}：旧密钥；{@code MUGSUN_SM4_NEW_KEY}：新密钥（须为 {@link Sm4Util#parseKey} 认可的格式）</li>
 *   <li>{@code MUGSUN_SM4_OLD_KEY_LEGACY_PADDED=true}：旧密钥按旧版「不足补零、超出截断」派生（旧短密钥 / 旧回落值的数据）</li>
 *   <li>{@code MUGSUN_MIGRATE_JDBC_URL}、{@code MUGSUN_MIGRATE_DB_USER}、{@code MUGSUN_MIGRATE_DB_PASSWORD}</li>
 * </ul>
 * 参数：{@code --dry-run}（默认，只统计不写库）或 {@code --apply}（单事务写库，有任何一条解不开就整体回滚）。
 * 每个字段输出：总数、需重加密、已是新密钥、历史明文、解不开及其主键。只按 id 改行，不打印 key、明文或密文。
 * 新旧两把钥都能解开的值按「解不开」处理，避免重跑时二次加密。
 * <p>只读当前连接 search_path（或默认 schema）下的表：多 schema 部署请用目标 schema 的账号运行，
 * 或在 JDBC URL 里带 {@code currentSchema}。
 * <p>多租户独立库（sys_tenant_datasource）要对每个库各跑一次。
 */
public final class Sm4KeyMigrationTool {

	/** 表 → 加密字段（与实体上 @Column(typeHandler = Sm4TypeHandler.class) 一一对应，Sm4KeyMigrationToolTest 校验） */
	public static final Map<String, List<String>> FIELDS = fields();

	private static Map<String, List<String>> fields() {
		Map<String, List<String>> m = new LinkedHashMap<>();
		m.put("ai_model", List.of("api_key", "secret_key"));
		m.put("ai_secret", List.of("secret_key"));
		m.put("ai_datasource", List.of("password"));
		m.put("ai_vector_store", List.of("password"));
		m.put("ai_mcp_tool", List.of("api_key"));
		m.put("sys_user", List.of("id_card"));
		m.put("sys_tenant_datasource", List.of("ds_password"));
		m.put("sys_notify_channel", List.of("secret"));
		m.put("sys_oauth_client", List.of("client_secret"));
		m.put("sys_api_key", List.of("secret_key"));
		m.put("sys_oss", List.of("access_key", "secret_key"));
		m.put("gis_map_provider", List.of("api_key", "secret"));
		return java.util.Collections.unmodifiableMap(m);
	}

	/** 单个字段的统计 */
	public static final class Counts {
		public long total;
		public long reencrypted;
		public long alreadyNew;
		public long plaintext;
		public long failed;
		public boolean missing;
		/** 解不开的行的主键（只记主键，不记值） */
		public final List<Object> failedIds = new ArrayList<>();

		@Override
		public String toString() {
			return missing ? "表或列不存在，跳过"
				: "总数=" + total + " 需重加密=" + reencrypted + " 已是新密钥=" + alreadyNew
				+ " 历史明文=" + plaintext + " 解不开=" + failed
				+ (failedIds.isEmpty() ? "" : " 解不开的主键=" + failedIds);
		}
	}

	private final SM4 oldKey;
	private final SM4 newKey;

	public Sm4KeyMigrationTool(byte[] oldKey, byte[] newKey) {
		if (Arrays.equals(oldKey, newKey)) {
			throw new IllegalArgumentException("新旧密钥相同，拒绝执行");
		}
		this.oldKey = SmUtil.sm4(oldKey.clone());
		this.newKey = SmUtil.sm4(newKey.clone());
	}

	public static void main(String[] args) {
		try {
			System.exit(run(args, System::getenv));
		} catch (IllegalArgumentException | IllegalStateException e) {
			// 自己抛的异常信息不含 key、明文、密文
			System.err.println("迁移中止：" + e.getMessage());
			System.exit(1);
		} catch (SQLException e) {
			// 驱动的报错可能带参数值，只输出 SQLState 和错误码
			System.err.println("迁移中止：数据库错误 SQLState=" + e.getSQLState() + " code=" + e.getErrorCode() + "，已回滚");
			System.exit(1);
		} catch (RuntimeException e) {
			// 第三方异常（如 hutool CryptoException）的信息和堆栈可能带值，只输出类名
			System.err.println("迁移中止：" + e.getClass().getName() + "，已回滚");
			System.exit(1);
		}
	}

	/** 只认 --dry-run（默认）和 --apply；key 只从环境变量读，命令行出现其他参数一律拒绝 */
	static int run(String[] args, Function<String, String> env) throws SQLException {
		boolean apply = false;
		boolean dryRun = false;
		for (String a : args) {
			if ("--apply".equals(a)) {
				apply = true;
			} else if ("--dry-run".equals(a)) {
				dryRun = true;
			} else {
				throw new IllegalArgumentException("不认识的参数（key 只能用环境变量传入，不接受命令行参数），拒绝执行");
			}
		}
		if (apply && dryRun) {
			throw new IllegalArgumentException("参数冲突：--dry-run 和 --apply 只能二选一");
		}
		Sm4KeyMigrationTool tool = fromEnv(env);
		try (Connection c = DriverManager.getConnection(require(env, "MUGSUN_MIGRATE_JDBC_URL"),
			require(env, "MUGSUN_MIGRATE_DB_USER"), env.apply("MUGSUN_MIGRATE_DB_PASSWORD"))) {
			Map<String, Counts> result = tool.run(c, apply);
			long failed = result.values().stream().mapToLong(x -> x.failed).sum();
			result.forEach((k, v) -> System.out.println(k + "  " + v));
			System.out.println(apply
				? (failed == 0 ? "已写库（单事务提交）" : "有 " + failed + " 条解不开，已整体回滚，什么都没改")
				: "dry-run：没有写库；确认「解不开=0」后加 --apply 执行");
			return failed > 0 ? 2 : 0;
		}
	}

	static Sm4KeyMigrationTool fromEnv(Function<String, String> env) {
		String old = require(env, "MUGSUN_SM4_OLD_KEY");
		byte[] oldRaw = "true".equalsIgnoreCase(env.apply("MUGSUN_SM4_OLD_KEY_LEGACY_PADDED"))
			? Sm4Util.legacyPaddedKey(old)
			: Sm4Util.parseKey(old);
		return new Sm4KeyMigrationTool(oldRaw, Sm4Util.parseKey(require(env, "MUGSUN_SM4_NEW_KEY")));
	}

	private static String require(Function<String, String> env, String name) {
		String v = env.apply(name);
		if (v == null || v.isBlank()) {
			throw new IllegalArgumentException("缺少环境变量 " + name);
		}
		return v;
	}

	/** 逐表逐字段迁移；apply=false 只统计。apply=true 时单事务，有解不开的就回滚 */
	public Map<String, Counts> run(Connection c, boolean apply) throws SQLException {
		boolean auto = c.getAutoCommit();
		c.setAutoCommit(false);
		Map<String, Counts> out = new LinkedHashMap<>();
		try {
			for (var e : FIELDS.entrySet()) {
				for (String col : e.getValue()) {
					out.put(e.getKey() + "." + col, migrateColumn(c, e.getKey(), col, apply));
				}
			}
			boolean anyFailed = out.values().stream().anyMatch(x -> x.failed > 0);
			if (apply && !anyFailed) {
				c.commit();
			} else {
				c.rollback();
			}
			return out;
		} catch (SQLException | RuntimeException ex) {
			c.rollback();
			throw ex;
		} finally {
			c.setAutoCommit(auto);
		}
	}

	private Counts migrateColumn(Connection c, String table, String col, boolean apply) throws SQLException {
		Counts n = new Counts();
		if (!columnExists(c, table, col)) {
			n.missing = true;
			return n;
		}
		List<Object[]> updates = new ArrayList<>();
		// 表名列名来自上面的常量清单，不来自输入
		try (PreparedStatement ps = c.prepareStatement("SELECT id, " + col + " FROM " + table
			+ " WHERE " + col + " IS NOT NULL AND " + col + " <> ''");
			 ResultSet rs = ps.executeQuery()) {
			while (rs.next()) {
				n.total++;
				Object id = rs.getObject(1);
				String v = rs.getString(2);
				if (!Sm4Util.looksLikeCipher(v)) {
					// 加密上线前的历史明文：直接用新密钥加密
					n.plaintext++;
					updates.add(new Object[]{newKey.encryptBase64(v), id});
					continue;
				}
				String plain = tryDecrypt(oldKey, v);
				boolean newOk = tryDecrypt(newKey, v) != null;
				if (plain != null && newOk) {
					// 两把钥都能解（重跑时旧钥碰巧解出合法 UTF-8）：不敢判断，交给人工
					n.failed++;
					n.failedIds.add(id);
				} else if (plain != null) {
					n.reencrypted++;
					updates.add(new Object[]{newKey.encryptBase64(plain), id});
				} else if (newOk) {
					n.alreadyNew++;
				} else {
					n.failed++;
					n.failedIds.add(id);
				}
			}
		}
		if (apply && !updates.isEmpty()) {
			try (PreparedStatement up = c.prepareStatement("UPDATE " + table + " SET " + col + " = ? WHERE id = ?")) {
				for (Object[] u : updates) {
					up.setString(1, (String) u[0]);
					up.setObject(2, u[1]);
					up.addBatch();
				}
				up.executeBatch();
			}
		}
		return n;
	}

	/** 解不开（含解出来不是合法 UTF-8，多半是错密钥碰巧填充合法）返回 null */
	static String tryDecrypt(SM4 sm4, String cipher) {
		try {
			byte[] raw = sm4.decrypt(cipher);
			return StandardCharsets.UTF_8.newDecoder()
				.onMalformedInput(CodingErrorAction.REPORT)
				.onUnmappableCharacter(CodingErrorAction.REPORT)
				.decode(ByteBuffer.wrap(raw)).toString();
		} catch (CharacterCodingException | RuntimeException e) {
			return null;
		}
	}

	private static boolean columnExists(Connection c, String table, String col) throws SQLException {
		var md = c.getMetaData();
		for (String t : new String[]{table, table.toUpperCase(java.util.Locale.ROOT)}) {
			for (String k : new String[]{col, col.toUpperCase(java.util.Locale.ROOT)}) {
				try (ResultSet rs = md.getColumns(c.getCatalog(), null, t, k)) {
					if (rs.next()) {
						return true;
					}
				}
			}
		}
		return false;
	}
}
