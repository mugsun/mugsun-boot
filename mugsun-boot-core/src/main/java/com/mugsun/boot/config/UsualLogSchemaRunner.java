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
 * 已灌过 0.1 基线的库不会重跑脚本。这里在启动时补建通用日志表，已有则跳过。
 */
@Component
@Order(0)
public class UsualLogSchemaRunner implements CommandLineRunner {

	private final DataSource dataSource;

	public UsualLogSchemaRunner(DataSource dataSource) {
		this.dataSource = dataSource;
	}

	@Override
	public void run(String... args) throws Exception {
		try (Connection connection = dataSource.getConnection()) {
			if (tableExists(connection, "sys_usual_log")) {
				return;
			}
			boolean dameng = isDameng(connection);
			String deleted = dameng ? "INT DEFAULT 0 NOT NULL" : "INT NOT NULL DEFAULT 0";
			execute(connection, "CREATE TABLE sys_usual_log ("
				+ "id BIGINT PRIMARY KEY,"
				+ "tenant_id VARCHAR(12),"
				+ "log_level VARCHAR(16) NOT NULL,"
				+ "log_id VARCHAR(64),"
				+ "log_data VARCHAR(1000),"
				+ "request_uri VARCHAR(255),"
				+ "method VARCHAR(16),"
				+ "remote_ip VARCHAR(64),"
				+ "user_agent VARCHAR(512),"
				+ "create_time TIMESTAMP,"
				+ "update_time TIMESTAMP,"
				+ "is_deleted " + deleted
				+ ")");
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
}
