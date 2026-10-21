package com.mugsun.boot.common.crypto;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import com.mugsun.boot.config.DeployProfiles;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 字段加密密钥启动自检：所有单例（含 Flyway 迁移）就绪后、Web 开始监听前，
 * 每个加密字段最多读 {@value #SAMPLE} 行（SQL 带 LIMIT），其中的密文用当前 MUGSUN_SM4_KEY 试解，解不开就拒绝启动。
 * <p>主要拦两种情况：换了密钥没迁移；旧部署配的是 32 位 hex / 24 位 base64（旧版只取前 16 个字符，
 * 本版按编码解码，等于悄悄换了一把钥）。空库（没有任何密文）直接放行。
 * 报错只写表名、字段和主键，不写密钥、明文或密文。
 * <p>跳过开关 {@code mugsun.crypto.sm4-self-check=false} 只在非生产生效，
 * 生产判定与启动守卫共用 {@link DeployProfiles}；生产环境下开关被忽略，照常自检。
 */
@Component
public class Sm4KeySelfCheck implements SmartInitializingSingleton {

	private static final Logger log = LoggerFactory.getLogger(Sm4KeySelfCheck.class);

	/** 每个字段最多读几行 */
	static final int SAMPLE = 3;

	static final String SKIP_PROPERTY = "mugsun.crypto.sm4-self-check";

	private final ObjectProvider<DataSource> dataSource;

	private final Environment environment;

	public Sm4KeySelfCheck(ObjectProvider<DataSource> dataSource, Environment environment) {
		this.dataSource = dataSource;
		this.environment = environment;
	}

	/** 是否跳过：只有非生产且显式关掉才跳过 */
	static boolean skip(Environment env) {
		boolean disabled = !env.getProperty(SKIP_PROPERTY, Boolean.class, true);
		if (!disabled) {
			return false;
		}
		if (DeployProfiles.isProduction(env)) {
			log.warn("{}=false 只在非生产环境生效，当前按生产处理，照常自检", SKIP_PROPERTY);
			return false;
		}
		log.warn("{}=false：非生产环境跳过 SM4 字段加密密钥自检", SKIP_PROPERTY);
		return true;
	}

	@Override
	public void afterSingletonsInstantiated() {
		if (skip(environment)) {
			return;
		}
		DataSource ds = dataSource.getIfAvailable();
		if (ds == null) {
			return;
		}
		int checked = check(ds, Sm4KeyMigrationTool.FIELDS);
		log.info("SM4 字段加密密钥自检通过（抽查 {} 条已有密文）", checked);
	}

	/** 返回抽查条数；有一条解不开即抛 IllegalStateException */
	static int check(DataSource ds, Map<String, List<String>> fields) {
		int checked = 0;
		try (Connection c = ds.getConnection()) {
			for (var e : fields.entrySet()) {
				for (String col : e.getValue()) {
					if (!columnExists(c, e.getKey(), col)) {
						continue;
					}
					// 表名列名来自常量清单
					try (PreparedStatement ps = c.prepareStatement("SELECT id, " + col + " FROM " + e.getKey()
						+ " WHERE " + col + " IS NOT NULL AND " + col + " <> '' LIMIT " + SAMPLE)) {
						ps.setMaxRows(SAMPLE);
						try (ResultSet rs = ps.executeQuery()) {
							while (rs.next()) {
								String v = rs.getString(2);
								if (!Sm4Util.looksLikeCipher(v)) {
									continue;
								}
								checked++;
								if (!Sm4Util.canDecrypt(v)) {
									throw new IllegalStateException("MUGSUN_SM4_KEY 解不开库里已有的加密数据（" + e.getKey() + "." + col
										+ " 主键 " + rs.getObject(1) + "），拒绝启动。可能是换了密钥没迁移，或旧部署配的是 32 位 hex / "
										+ "24 位 base64（旧版只取前 16 个字符）：请按文档「迁移」设 MUGSUN_SM4_OLD_KEY_LEGACY_PADDED=true "
										+ "跑 Sm4KeyMigrationTool，或填回原来的密钥");
								}
							}
						}
					}
				}
			}
		} catch (SQLException ex) {
			throw new IllegalStateException("SM4 字段加密密钥自检读库失败（SQLState=" + ex.getSQLState() + "），拒绝启动");
		}
		return checked;
	}

	private static boolean columnExists(Connection c, String table, String col) throws SQLException {
		var md = c.getMetaData();
		for (String t : new String[]{table, table.toUpperCase(Locale.ROOT)}) {
			for (String k : new String[]{col, col.toUpperCase(Locale.ROOT)}) {
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
