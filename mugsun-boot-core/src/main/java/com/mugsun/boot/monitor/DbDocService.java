package com.mugsun.boot.monitor;

import com.mugsun.core.tool.exception.ServiceException;
import org.springframework.stereotype.Service;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 在线数据库文档：JDBC DatabaseMetaData 内省主库全部表/列/注释，渲染 markdown（在线查看 + 下载）。
 * <p>选型：自研 DatabaseMetaData 路线（mybatis-flex-codegen 已 optional 在 pom、零新依赖风险、
 * 输出格式可控），不引 screw 新依赖。跨方言走 JDBC 元数据（与 DdlService 内省先例一致）。
 */
@Service
public class DbDocService {

	private static final DateTimeFormatter FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

	/** 元数据内部表（不纳入业务文档） */
	private static final String FLYWAY_TABLE = "flyway_schema_history";

	private final DataSource dataSource;

	public DbDocService(DataSource dataSource) {
		this.dataSource = dataSource;
	}

	/** 全库表文档 markdown：表按名称序，列含类型/可空/注释 */
	public String markdown() {
		StringBuilder sb = new StringBuilder();
		sb.append("# 数据库文档\n\n");
		sb.append("> 生成时间：").append(LocalDateTime.now().format(FMT)).append("\n\n");
		try (Connection c = dataSource.getConnection()) {
			DatabaseMetaData md = c.getMetaData();
			Map<String, String> tables = tables(md, c);
			sb.append("共 ").append(tables.size()).append(" 张表\n\n");
			tables.forEach((table, remark) -> appendTable(sb, md, c, table, remark));
		} catch (SQLException e) {
			throw new ServiceException("读取数据库元数据失败：" + e.getMessage());
		}
		return sb.toString();
	}

	/** 表名 → 表注释（LinkedHashMap 保序后按名排序） */
	private Map<String, String> tables(DatabaseMetaData md, Connection c) throws SQLException {
		Map<String, String> tables = new LinkedHashMap<>();
		try (ResultSet rs = md.getTables(c.getCatalog(), c.getSchema(), "%", new String[]{"TABLE"})) {
			while (rs.next()) {
				String name = rs.getString("TABLE_NAME");
				if (name == null || FLYWAY_TABLE.equalsIgnoreCase(name)) {
					continue;
				}
				tables.put(name, rs.getString("REMARKS"));
			}
		}
		Map<String, String> sorted = new LinkedHashMap<>();
		tables.entrySet().stream().sorted(Map.Entry.comparingByKey())
			.forEach(e -> sorted.put(e.getKey(), e.getValue()));
		return sorted;
	}

	private void appendTable(StringBuilder sb, DatabaseMetaData md, Connection c, String table, String remark) {
		sb.append("## ").append(table);
		if (remark != null && !remark.isBlank()) {
			sb.append("：").append(remark);
		}
		sb.append("\n\n");
		sb.append("| 列名 | 类型 | 可空 | 注释 |\n");
		sb.append("| --- | --- | --- | --- |\n");
		try (ResultSet rs = md.getColumns(c.getCatalog(), c.getSchema(), table, null)) {
			while (rs.next()) {
				sb.append("| ").append(rs.getString("COLUMN_NAME"))
					.append(" | ").append(rs.getString("TYPE_NAME"));
				int size = rs.getInt("COLUMN_SIZE");
				if (size > 0) {
					sb.append("(").append(size).append(")");
				}
				sb.append(" | ").append("YES".equalsIgnoreCase(rs.getString("IS_NULLABLE")) ? "是" : "否")
					.append(" | ").append(nz(rs.getString("REMARKS")))
					.append(" |\n");
			}
		} catch (SQLException e) {
			sb.append("| （读取列元数据失败：").append(e.getMessage()).append("） | | | |\n");
		}
		sb.append("\n");
	}

	/** markdown 表格单元格转义（注释含竖线/换行会破坏表格） */
	private String nz(String s) {
		return s == null ? "" : s.replace("|", "\\|").replace("\n", " ").trim();
	}
}
