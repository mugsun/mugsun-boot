package com.mugsun.boot.gen;

import com.mybatisflex.codegen.Generator;
import com.mybatisflex.codegen.config.GlobalConfig;
import com.mybatisflex.codegen.entity.Column;
import com.mybatisflex.codegen.entity.Table;
import com.mugsun.core.tool.exception.ServiceException;
import org.springframework.stereotype.Service;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 库表内省：列出数据源全部表及列元数据，供代码生成选表导入。
 */
@Service
public class GenService {

	private final DataSource dataSource;

	public GenService(DataSource dataSource) {
		this.dataSource = dataSource;
	}

	/**
	 * 列出数据源全部表及列元数据。
	 * PG 系（PostgreSQL / 金仓 / openGauss）走 information_schema。
	 * 社区金仓和 openGauss-lite 的 JDBC {@code DatabaseMetaData} 会去查残缺的 pg_catalog（例如 lite 没有 snapshot），
	 * 与 {@link DdlService} 同一口径，不走 Flex Generator。
	 */
	public List<Map<String, Object>> tables() {
		SqlDialect dialect = DbDialects.of(dataSource);
		if (dialect == SqlDialect.POSTGRES) {
			return tablesViaInformationSchema();
		}
		if (dialect == SqlDialect.ORACLE) {
			return tablesViaAllTabColumns();
		}
		GlobalConfig config = new GlobalConfig();
		config.getPackageConfig().setBasePackage("com.mugsun.preview");
		List<Table> tables = new Generator(dataSource, config).getTables();
		List<Map<String, Object>> result = new ArrayList<>();
		for (Table t : tables) {
			result.add(tableMeta(t));
		}
		result.sort(Comparator.comparing(m -> String.valueOf(m.get("name"))));
		return result;
	}

	private List<Map<String, Object>> tablesViaInformationSchema() {
		String sql = "SELECT c.table_name, c.column_name, c.data_type "
			+ "FROM information_schema.columns c "
			+ "WHERE c.table_schema = current_schema() "
			+ "AND c.table_name IN ("
			+ "SELECT t.table_name FROM information_schema.tables t "
			+ "WHERE t.table_type = 'BASE TABLE' AND t.table_schema = current_schema()) "
			+ "ORDER BY c.table_name, c.ordinal_position";
		Map<String, Map<String, Object>> grouped = new LinkedHashMap<>();
		try (Connection connection = dataSource.getConnection();
			 PreparedStatement statement = connection.prepareStatement(sql);
			 ResultSet rows = statement.executeQuery()) {
			while (rows.next()) {
				String tableName = rows.getString(1);
				Map<String, Object> table = grouped.computeIfAbsent(tableName, name -> {
					Map<String, Object> map = new LinkedHashMap<>();
					map.put("name", name);
					map.put("comment", "");
					map.put("columns", new ArrayList<Map<String, Object>>());
					return map;
				});
				Map<String, Object> column = new LinkedHashMap<>();
				String columnName = rows.getString(2);
				column.put("name", columnName);
				column.put("property", columnName);
				column.put("type", rows.getString(3));
				column.put("comment", "");
				@SuppressWarnings("unchecked")
				List<Map<String, Object>> columns = (List<Map<String, Object>>) table.get("columns");
				columns.add(column);
			}
		} catch (SQLException e) {
			throw new ServiceException("读取库表失败：" + e.getMessage());
		}
		List<Map<String, Object>> result = new ArrayList<>(grouped.values());
		result.sort(Comparator.comparing(m -> String.valueOf(m.get("name"))));
		return result;
	}

	/** 达梦 / Oracle：ALL_TAB_COLUMNS。达梦 JDBC 的 getTables 会去查不存在的 ##PLAN_TABLE。 */
	private List<Map<String, Object>> tablesViaAllTabColumns() {
		String sql = "SELECT table_name, column_name, data_type FROM all_tab_columns "
			+ "WHERE owner = SYS_CONTEXT('USERENV','CURRENT_SCHEMA') "
			+ "ORDER BY table_name, column_id";
		Map<String, Map<String, Object>> grouped = new LinkedHashMap<>();
		try (Connection connection = dataSource.getConnection();
			 PreparedStatement statement = connection.prepareStatement(sql);
			 ResultSet rows = statement.executeQuery()) {
			while (rows.next()) {
				String tableName = rows.getString(1);
				Map<String, Object> table = grouped.computeIfAbsent(tableName, name -> {
					Map<String, Object> map = new LinkedHashMap<>();
					map.put("name", name);
					map.put("comment", "");
					map.put("columns", new ArrayList<Map<String, Object>>());
					return map;
				});
				Map<String, Object> column = new LinkedHashMap<>();
				column.put("name", rows.getString(2));
				column.put("property", rows.getString(2));
				column.put("type", rows.getString(3));
				column.put("comment", "");
				@SuppressWarnings("unchecked")
				List<Map<String, Object>> columns = (List<Map<String, Object>>) table.get("columns");
				columns.add(column);
			}
		} catch (SQLException e) {
			throw new ServiceException("读取库表失败：" + e.getMessage());
		}
		List<Map<String, Object>> result = new ArrayList<>(grouped.values());
		result.sort(Comparator.comparing(m -> String.valueOf(m.get("name"))));
		return result;
	}

	private Map<String, Object> tableMeta(Table t) {
		List<Map<String, Object>> cols = new ArrayList<>();
		if (t.getColumns() != null) {
			for (Column c : t.getColumns()) {
				Map<String, Object> col = new LinkedHashMap<>();
				col.put("name", c.getName());
				col.put("property", c.getProperty());
				col.put("type", c.getPropertyType());
				col.put("comment", c.getComment());
				cols.add(col);
			}
		}
		Map<String, Object> map = new LinkedHashMap<>();
		map.put("name", t.getName());
		map.put("comment", t.getComment());
		map.put("columns", cols);
		return map;
	}
}
