package com.mugsun.boot.config;

import com.mugsun.boot.demo.ConditionalOnNotDemo;

import org.springframework.boot.CommandLineRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.Statement;

/**
 * 已灌过 0.1 基线的库不会重跑脚本。这里在启动时补建顶部菜单表，已有则跳过。
 */
@Component
@Order(0)
@ConditionalOnNotDemo
public class TopMenuSchemaRunner implements CommandLineRunner {

	private final DataSource dataSource;

	public TopMenuSchemaRunner(DataSource dataSource) {
		this.dataSource = dataSource;
	}

	@Override
	public void run(String... args) throws Exception {
		try (Connection connection = dataSource.getConnection()) {
			boolean dameng = isDameng(connection);
			if (!tableExists(connection, "sys_top_menu")) {
				execute(connection, topMenuDdl(dameng));
			}
			if (!tableExists(connection, "sys_top_menu_menu")) {
				execute(connection, linkDdl(dameng));
			}
		}
	}

	private static boolean isDameng(Connection connection) throws Exception {
		String product = connection.getMetaData().getDatabaseProductName();
		return product != null && product.toLowerCase().contains("dm");
	}

	private static boolean tableExists(Connection connection, String table) throws Exception {
		DatabaseMetaData meta = connection.getMetaData();
		if (exists(meta, table) || exists(meta, table.toUpperCase())) {
			return true;
		}
		return false;
	}

	private static boolean exists(DatabaseMetaData meta, String table) throws Exception {
		try (ResultSet rs = meta.getTables(null, null, table, new String[]{"TABLE"})) {
			return rs.next();
		}
	}

	private static void execute(Connection connection, String sql) throws Exception {
		try (Statement statement = connection.createStatement()) {
			statement.execute(sql);
		}
	}

	private static String topMenuDdl(boolean dameng) {
		String sort = dameng ? "INT DEFAULT 0 NOT NULL" : "INT NOT NULL DEFAULT 0";
		String flag = dameng ? "INT DEFAULT 0 NOT NULL" : "INT NOT NULL DEFAULT 0";
		String deleted = dameng ? "INT DEFAULT 0 NOT NULL" : "INT NOT NULL DEFAULT 0";
		return "CREATE TABLE sys_top_menu ("
			+ "id BIGINT PRIMARY KEY,"
			+ "tenant_id VARCHAR(12),"
			+ "code VARCHAR(64) NOT NULL,"
			+ "name VARCHAR(64) NOT NULL,"
			+ "icon VARCHAR(64),"
			+ "path VARCHAR(128),"
			+ "sort " + sort + ","
			+ "is_main " + flag + ","
			+ "create_time TIMESTAMP,"
			+ "update_time TIMESTAMP,"
			+ "is_deleted " + deleted
			+ ")";
	}

	private static String linkDdl(boolean dameng) {
		String deleted = dameng ? "INT DEFAULT 0 NOT NULL" : "INT NOT NULL DEFAULT 0";
		return "CREATE TABLE sys_top_menu_menu ("
			+ "id BIGINT PRIMARY KEY,"
			+ "top_menu_id BIGINT NOT NULL,"
			+ "menu_id BIGINT NOT NULL,"
			+ "create_time TIMESTAMP,"
			+ "update_time TIMESTAMP,"
			+ "is_deleted " + deleted
			+ ")";
	}
}
