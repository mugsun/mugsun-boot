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
 * 已灌过 0.1 基线的库不会重跑脚本。这里补登录日志的登录方式列，已有则跳过。
 */
@Component
@Order(0)
public class LoginLogSchemaRunner implements CommandLineRunner {

	private final DataSource dataSource;

	public LoginLogSchemaRunner(DataSource dataSource) {
		this.dataSource = dataSource;
	}

	@Override
	public void run(String... args) throws Exception {
		try (Connection connection = dataSource.getConnection()) {
			if (columnExists(connection, "sys_login_log", "grant_type")) {
				return;
			}
			execute(connection, "ALTER TABLE sys_login_log ADD grant_type VARCHAR(32)");
		}
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
