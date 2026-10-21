package com.mugsun.boot.common.crypto;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

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
 * 从 15 个加密字段里各抽一条已有密文，用当前 MUGSUN_SM4_KEY 试解，解不开就拒绝启动。
 * <p>主要拦两种情况：换了密钥没迁移；旧部署配的是 32 位 hex / 24 位 base64（旧版只取前 16 个字符，
 * 本版按编码解码，等于悄悄换了一把钥）。空库（没有任何密文）直接放行。
 * 报错只写表名、字段和主键，不写密钥、明文或密文。
 */
@Component
@ConditionalOnProperty(name = "mugsun.crypto.sm4-self-check", havingValue = "true", matchIfMissing = true)
public class Sm4KeySelfCheck implements SmartInitializingSingleton {

	private static final Logger log = LoggerFactory.getLogger(Sm4KeySelfCheck.class);

	private final ObjectProvider<DataSource> dataSource;

	public Sm4KeySelfCheck(ObjectProvider<DataSource> dataSource) {
		this.dataSource = dataSource;
	}

	@Override
	public void afterSingletonsInstantiated() {
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
						+ " WHERE " + col + " IS NOT NULL AND " + col + " <> ''")) {
						ps.setMaxRows(20);
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
								break;
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
