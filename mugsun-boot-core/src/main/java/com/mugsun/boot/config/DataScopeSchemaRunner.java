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
 * 已灌过 0.1 基线的库不会重跑脚本。这里在启动时补建数据权限规则表，已有则跳过。
 */
@Component
@Order(0)
@ConditionalOnNotDemo
public class DataScopeSchemaRunner implements CommandLineRunner {

	private final DataSource dataSource;

	public DataScopeSchemaRunner(DataSource dataSource) {
		this.dataSource = dataSource;
	}

	@Override
	public void run(String... args) throws Exception {
		try (Connection connection = dataSource.getConnection()) {
			if (tableExists(connection, "sys_data_scope")) {
				return;
			}
			boolean dameng = isDameng(connection);
			execute(connection, ddl(dameng));
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

	private static String ddl(boolean dameng) {
		String scopeType = dameng ? "INT DEFAULT 1 NOT NULL" : "INT NOT NULL DEFAULT 1";
		String status = dameng ? "INT DEFAULT 1 NOT NULL" : "INT NOT NULL DEFAULT 1";
		String deleted = dameng ? "INT DEFAULT 0 NOT NULL" : "INT NOT NULL DEFAULT 0";
		return "CREATE TABLE sys_data_scope ("
			+ "id BIGINT PRIMARY KEY,"
			+ "resource_code VARCHAR(64) NOT NULL,"
			+ "scope_name VARCHAR(64) NOT NULL,"
			+ "menu_id BIGINT,"
			+ "table_name VARCHAR(64) NOT NULL,"
			+ "dept_column VARCHAR(64) NOT NULL,"
			+ "user_column VARCHAR(64) NOT NULL,"
			+ "scope_type " + scopeType + ","
			+ "status " + status + ","
			+ "remark VARCHAR(255),"
			+ "create_time TIMESTAMP,"
			+ "update_time TIMESTAMP,"
			+ "is_deleted " + deleted
			+ ")";
	}
}
