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
 * 已灌过 0.1 基线的库不会重跑脚本。这里补岗位类型，已有则跳过。
 */
@Component
@Order(0)
public class PostSchemaRunner implements CommandLineRunner {

	private final DataSource dataSource;

	public PostSchemaRunner(DataSource dataSource) {
		this.dataSource = dataSource;
	}

	@Override
	public void run(String... args) throws Exception {
		try (Connection connection = dataSource.getConnection()) {
			if (columnExists(connection, "sys_post", "category")) {
				return;
			}
			boolean dameng = isDameng(connection);
			String add = dameng ? "ADD " : "ADD COLUMN ";
			execute(connection, "ALTER TABLE sys_post " + add + "category VARCHAR(16)");
		}
	}

	private static boolean isDameng(Connection connection) throws Exception {
		String product = connection.getMetaData().getDatabaseProductName();
		return product != null && product.toLowerCase().contains("dm");
	}

	private static boolean columnExists(Connection connection, String table, String column) throws Exception {
		DatabaseMetaData meta = connection.getMetaData();
		return has(meta, table, column) || has(meta, table.toUpperCase(), column.toUpperCase());
	}

	private static boolean has(DatabaseMetaData meta, String table, String column) throws Exception {
		try (ResultSet rs = meta.getColumns(null, null, table, column)) {
			return rs.next();
		}
	}

	private static void execute(Connection connection, String sql) throws Exception {
		try (Statement statement = connection.createStatement()) {
			statement.execute(sql);
		}
	}
}
