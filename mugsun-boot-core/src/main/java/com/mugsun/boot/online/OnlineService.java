package com.mugsun.boot.online;

import cn.hutool.core.util.IdUtil;
import com.mugsun.boot.gen.entity.GenColumn;
import com.mugsun.boot.gen.entity.GenTable;
import com.mugsun.boot.gen.mapper.GenColumnMapper;
import com.mugsun.boot.gen.mapper.GenTableMapper;
import com.mugsun.boot.tenant.TenantContext;
import com.mybatisflex.core.paginate.Page;
import com.mybatisflex.core.query.QueryWrapper;
import com.mybatisflex.core.row.Db;
import com.mybatisflex.core.row.Row;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 在线（低代码）运行时引擎：复用 gen_table/gen_column 统一元数据，经 Flex Db + Row **无实体**对真实业务物理表
 * 做泛化增删改查（结构化列，非 JSON blob）。改元数据配置即时生效、零发布；数据可按字段查询。
 * <p>无实体路径不走 Flex 逻辑删除/租户插件，故此处按元数据手工附加 is_deleted=0 / tenant_id / 审计。
 */
@Service
public class OnlineService {

	private final GenTableMapper tableMapper;
	private final GenColumnMapper columnMapper;
	private final javax.sql.DataSource dataSource;

	public OnlineService(GenTableMapper tableMapper, GenColumnMapper columnMapper, javax.sql.DataSource dataSource) {
		this.tableMapper = tableMapper;
		this.columnMapper = columnMapper;
		this.dataSource = dataSource;
	}

	/** 表单元数据：表 + 字段配置，供前端运行时渲染 */
	public Map<String, Object> meta(Long tableId) {
		return Map.of("table", table(tableId), "columns", columns(tableId));
	}

	/** 运行时分页查询（is_deleted=0 + 租户 + is_query 字段动态条件） */
	public Page<Row> page(Long tableId, long pageNum, long pageSize, Map<String, String> query) {
		GenTable t = table(tableId);
		List<GenColumn> cols = columns(tableId);
		Set<String> names = physicalNames(t.getTableName());
		String tableName = t.getTableName();
		QueryWrapper qw = QueryWrapper.create().from(tableName);
		// 用户排序：列名走白名单（表实际列）+ 方向校验，拦 orderBy 注入；缺省 id 降序
		String sortField = query == null ? null : query.get("sortField");
		if (sortField != null && !sortField.isBlank()) {
			String col = com.mugsun.boot.security.SqlSafeUtil.safeColumn(sortField, names);
			qw.orderBy(col, "ASC".equals(com.mugsun.boot.security.SqlSafeUtil.safeDirection(query.get("sortOrder"))));
		} else {
			qw.orderBy("id", false);
		}
		applyScope(qw, names);
		for (GenColumn c : cols) {
			if (isOne(c.getIsQuery()) && query != null) {
				String v = query.get(c.getColumnName());
				if (v != null && !v.isBlank()) {
					// 列名来自 gen_column 元数据，仍过标识符校验（防元数据被污染的二阶注入）
					String col = com.mugsun.boot.security.SqlSafeUtil.safeColumn(c.getColumnName(), names);
					if ("LIKE".equalsIgnoreCase(c.getQueryType())) {
						qw.and(col + " LIKE ?", "%" + v + "%");
					} else {
						qw.and(col + " = ?", coerce(v, c.getJavaType()));
					}
				}
			}
		}
		return Db.paginate(tableName, pageNum, Math.min(pageSize, 500), qw);
	}

	/** 运行时详情（与 page 同口径附加租户 + 逻辑删除范围，防跨租户/越删读） */
	public Row detail(Long tableId, Object id) {
		GenTable t = table(tableId);
		Set<String> names = physicalNames(t.getTableName());
		String tableName = t.getTableName();
		QueryWrapper qw = QueryWrapper.create().from(tableName).where("id = ?", parseId(id));
		applyScope(qw, names);
		List<Row> rows = Db.selectListByQuery(tableName, qw);
		return rows.isEmpty() ? null : rows.get(0);
	}

	/** 运行时保存（新增/编辑）：仅可编辑列入库，落真实物理表结构化列 */
	public void save(Long tableId, Map<String, Object> data) {
		GenTable t = table(tableId);
		List<GenColumn> cols = columns(tableId);
		Set<String> names = physicalNames(t.getTableName());
		String tableName = t.getTableName();
		Object id = data.get("id");
		boolean isNew = id == null || id.toString().isBlank();
		Row row = isNew ? new Row() : Row.ofKey("id", parseId(id));
		for (GenColumn c : cols) {
			if (isOne(c.getIsEdit()) && data.containsKey(c.getColumnName())) {
				row.set(c.getColumnName(), data.get(c.getColumnName()));
			}
		}
		if (isNew) {
			row.set("id", IdUtil.getSnowflakeNextId());
			if (names.contains("create_time")) {
				row.set("create_time", LocalDateTime.now());
			}
			if (names.contains("is_deleted")) {
				row.set("is_deleted", 0);
			}
			if (names.contains("tenant_id") && TenantContext.current() != null) {
				row.set("tenant_id", TenantContext.current());
			}
			Db.insert(tableName, row);
		} else {
			// 编辑前校验目标行在当前租户 / 未删除范围内，防跨租户改
			QueryWrapper scope = QueryWrapper.create().from(tableName).where("id = ?", parseId(id));
			applyScope(scope, names);
			if (Db.selectListByQuery(tableName, scope).isEmpty()) {
				throw new IllegalArgumentException("记录不存在或无权限：" + id);
			}
			if (names.contains("update_time")) {
				row.set("update_time", LocalDateTime.now());
			}
			Db.updateById(tableName, row);
		}
	}

	/** 运行时删除：有 is_deleted 列走逻辑删除，否则物理删除（均附加租户范围，防跨租户删） */
	public void remove(Long tableId, List<Long> ids) {
		if (ids == null || ids.isEmpty()) {
			return;
		}
		GenTable t = table(tableId);
		Set<String> names = physicalNames(t.getTableName());
		String tableName = t.getTableName();
		String placeholders = ids.stream().map(i -> "?").collect(Collectors.joining(","));
		List<Object> params = new ArrayList<>(ids);
		String tenantClause = "";
		if (names.contains("tenant_id") && TenantContext.current() != null) {
			tenantClause = " AND tenant_id = ?";
			params.add(TenantContext.current());
		}
		if (!names.contains("is_deleted")) {
			Db.updateBySql("DELETE FROM " + tableName + " WHERE id IN (" + placeholders + ")" + tenantClause,
				params.toArray());
			return;
		}
		String sql = "UPDATE " + tableName + " SET is_deleted = 1 WHERE id IN (" + placeholders + ")" + tenantClause;
		Db.updateBySql(sql, params.toArray());
	}

	/** 无实体路径手工附加租户 + 逻辑删除范围条件，与 page/detail/save/remove 统一口径 */
	private void applyScope(QueryWrapper qw, Set<String> names) {
		if (names.contains("is_deleted")) {
			qw.and("is_deleted = 0");
		}
		if (names.contains("tenant_id") && TenantContext.current() != null) {
			qw.and("tenant_id = ?", TenantContext.current());
		}
	}

	/** 元数据读取统一闸：标识符合法性 + 平台系统表黑名单（防存量被投毒元数据流入运行时）。
	 *  注意与 DdlService.guardNotProtected 的差异：DDL 侧含 gen_ 前缀（保护 gen_table/gen_column 元数据表），
	 *  运行时侧放行 gen_ 业务表（低代码建表产物），仅禁两张元数据表本体。 */
	private GenTable table(Long tableId) {
		GenTable t = tableMapper.selectOneById(tableId);
		if (t == null) {
			throw new IllegalArgumentException("在线表单不存在：" + tableId);
		}
		if (!com.mugsun.boot.gen.GenNaming.isIdentifier(t.getTableName())) {
			throw new IllegalArgumentException("非法表名：" + t.getTableName());
		}
		guardRuntimeTable(t.getTableName());
		return t;
	}

	/** 运行时黑名单：平台系统表前缀 + 元数据表本体（gen_table/gen_column） */
	private static final java.util.Set<String> RUNTIME_PROTECTED_PREFIXES = java.util.Set.of(
		"sys_", "flow_", "flyway_", "qrtz_", "blade_", "act_", "biz_", "help_", "viz_", "ai_");

	private void guardRuntimeTable(String tableName) {
		String low = tableName.toLowerCase();
		if ("gen_table".equals(low) || "gen_column".equals(low)) {
			throw new IllegalArgumentException("元数据表不可作为在线表单：" + tableName);
		}
		for (String p : RUNTIME_PROTECTED_PREFIXES) {
			if (low.startsWith(p)) {
				throw new IllegalArgumentException("平台系统表不可作为在线表单：" + tableName);
			}
		}
	}

	/** 物理表实际列名（JDBC 内省）：租户/逻辑删除判定以物理结构为准，不信元数据（防元数据缺列绕过隔离） */
	private Set<String> physicalNames(String tableName) {
		Set<String> names = new HashSet<>();
		try (java.sql.Connection c = dataSource.getConnection()) {
			java.sql.DatabaseMetaData md = c.getMetaData();
			try (java.sql.ResultSet rs = md.getColumns(c.getCatalog(), c.getSchema(), tableName.toLowerCase(), null)) {
				while (rs.next()) {
					names.add(rs.getString("COLUMN_NAME").toLowerCase());
				}
			}
			if (names.isEmpty()) {
				try (java.sql.ResultSet rs = md.getColumns(c.getCatalog(), c.getSchema(), tableName.toUpperCase(), null)) {
					while (rs.next()) {
						names.add(rs.getString("COLUMN_NAME").toLowerCase());
					}
				}
			}
		} catch (java.sql.SQLException e) {
			throw new IllegalStateException("读取表结构失败：" + e.getMessage());
		}
		if (names.isEmpty()) {
			throw new IllegalArgumentException("物理表不存在：" + tableName);
		}
		return names;
	}

	/** id 解析（非法 id 统一参数错误，防 NumberFormatException 落 500） */
	private Long parseId(Object id) {
		try {
			return Long.valueOf(id.toString());
		} catch (NumberFormatException e) {
			throw new IllegalArgumentException("非法 id：" + id);
		}
	}

	private List<GenColumn> columns(Long tableId) {
		return columnMapper.selectListByQuery(
			QueryWrapper.create().eq("table_id", tableId).orderBy("sort", true));
	}

	private boolean isOne(Integer v) {
		return v != null && v == 1;
	}

	/** 查询值按字段 Java 类型转型：数值列需绑定数值（String 绑定数值列在部分数据库会类型报错） */
	private Object coerce(String v, String javaType) {
		if (javaType == null) {
			return v;
		}
		try {
			return switch (javaType) {
				case "Integer", "Long", "Short" -> Long.valueOf(v.trim());
				case "BigDecimal" -> new BigDecimal(v.trim());
				case "Double", "Float" -> Double.valueOf(v.trim());
				default -> v;
			};
		} catch (NumberFormatException e) {
			return v;
		}
	}
}
