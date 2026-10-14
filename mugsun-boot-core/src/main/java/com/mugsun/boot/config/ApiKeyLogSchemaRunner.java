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
 * 已灌过 0.1 基线的库不会重跑脚本。这里在启动时补建密钥调用记录表，已有则跳过。
 */
@Component
@Order(0)
@ConditionalOnNotDemo
public class ApiKeyLogSchemaRunner implements CommandLineRunner {

	private final DataSource dataSource;

	public ApiKeyLogSchemaRunner(DataSource dataSource) {
		this.dataSource = dataSource;
	}

	@Override
	public void run(String... args) throws Exception {
		try (Connection connection = dataSource.getConnection()) {
			if (tableExists(connection, "sys_api_key_log")) {
				return;
			}
			boolean dameng = isDameng(connection);
			String status = dameng ? "INT DEFAULT 0 NOT NULL" : "INT NOT NULL DEFAULT 0";
			String deleted = dameng ? "INT DEFAULT 0 NOT NULL" : "INT NOT NULL DEFAULT 0";
			execute(connection, "CREATE TABLE sys_api_key_log ("
				+ "id BIGINT PRIMARY KEY,"
				+ "tenant_id VARCHAR(12),"
				+ "api_key_id BIGINT,"
				+ "access_key VARCHAR(64),"
				+ "key_name VARCHAR(64),"
				+ "request_uri VARCHAR(255),"
				+ "method VARCHAR(16),"
				+ "remote_ip VARCHAR(64),"
				+ "user_agent VARCHAR(512),"
				+ "params VARCHAR(1000),"
				+ "cost_ms INT,"
				+ "status " + status + ","
				+ "msg VARCHAR(255),"
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
