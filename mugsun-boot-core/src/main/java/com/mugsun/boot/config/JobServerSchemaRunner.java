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
 * 已灌过 0.1 基线的库不会重跑脚本。这里在启动时补建任务服务表，已有则跳过。
 */
@Component
@Order(0)
@ConditionalOnNotDemo
public class JobServerSchemaRunner implements CommandLineRunner {

	private final DataSource dataSource;

	public JobServerSchemaRunner(DataSource dataSource) {
		this.dataSource = dataSource;
	}

	@Override
	public void run(String... args) throws Exception {
		try (Connection connection = dataSource.getConnection()) {
			if (tableExists(connection, "sys_job_server")) {
				return;
			}
			boolean dameng = isDameng(connection);
			String deleted = dameng ? "INT DEFAULT 0 NOT NULL" : "INT NOT NULL DEFAULT 0";
			execute(connection, "CREATE TABLE sys_job_server ("
				+ "id BIGINT PRIMARY KEY,"
				+ "tenant_id VARCHAR(12),"
				+ "server_name VARCHAR(64) NOT NULL,"
				+ "server_url VARCHAR(200) NOT NULL,"
				+ "app_name VARCHAR(64) NOT NULL,"
				+ "app_password VARCHAR(128) NOT NULL,"
				+ "remark VARCHAR(200),"
				+ "sync_status INT,"
				+ "sync_msg VARCHAR(255),"
				+ "sync_time TIMESTAMP,"
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
