package com.mugsun.boot.config;

import org.springframework.boot.CommandLineRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.Statement;

/**
 * 已灌过 0.1 基线的库不会重跑脚本。这里在启动时补建接口权限表，已有则跳过。
 */
@Component
@Order(0)
public class ApiScopeSchemaRunner implements CommandLineRunner {

	private final DataSource dataSource;

	public ApiScopeSchemaRunner(DataSource dataSource) {
		this.dataSource = dataSource;
	}

	@Override
	public void run(String... args) throws Exception {
		try (Connection connection = dataSource.getConnection()) {
			boolean dameng = isDameng(connection);
			if (!tableExists(connection, "sys_api_scope")) {
				execute(connection, scopeDdl(dameng));
			}
			if (!tableExists(connection, "sys_role_api_scope")) {
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
		return exists(meta, table) || exists(meta, table.toUpperCase());
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

	private static String scopeDdl(boolean dameng) {
		String scopeType = dameng ? "INT DEFAULT 1 NOT NULL" : "INT NOT NULL DEFAULT 1";
		String status = dameng ? "INT DEFAULT 1 NOT NULL" : "INT NOT NULL DEFAULT 1";
		String deleted = dameng ? "INT DEFAULT 0 NOT NULL" : "INT NOT NULL DEFAULT 0";
		return "CREATE TABLE sys_api_scope ("
			+ "id BIGINT PRIMARY KEY,"
			+ "resource_code VARCHAR(64) NOT NULL,"
			+ "scope_name VARCHAR(64) NOT NULL,"
			+ "scope_path VARCHAR(128) NOT NULL,"
			+ "scope_type " + scopeType + ","
			+ "menu_id BIGINT,"
			+ "status " + status + ","
			+ "remark VARCHAR(255),"
			+ "create_time TIMESTAMP,"
			+ "update_time TIMESTAMP,"
			+ "is_deleted " + deleted
			+ ")";
	}

	private static String linkDdl(boolean dameng) {
		String deleted = dameng ? "INT DEFAULT 0 NOT NULL" : "INT NOT NULL DEFAULT 0";
		return "CREATE TABLE sys_role_api_scope ("
			+ "id BIGINT PRIMARY KEY,"
			+ "role_id BIGINT NOT NULL,"
			+ "api_scope_id BIGINT NOT NULL,"
			+ "create_time TIMESTAMP,"
			+ "update_time TIMESTAMP,"
			+ "is_deleted " + deleted
			+ ")";
	}
}
