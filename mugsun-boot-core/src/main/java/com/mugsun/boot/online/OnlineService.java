package com.mugsun.boot.online;

import cn.hutool.core.util.IdUtil;
import com.mugsun.boot.gen.entity.GenColumn;
import com.mugsun.boot.gen.entity.GenTable;
import com.mugsun.boot.gen.mapper.GenColumnMapper;
import com.mugsun.boot.gen.mapper.GenTableMapper;
import com.mugsun.boot.tenant.TenantContext;
import com.mugsun.core.tool.exception.ServiceException;
import com.mybatisflex.core.paginate.Page;
import com.mybatisflex.core.query.QueryWrapper;
import com.mybatisflex.core.row.Db;
import com.mybatisflex.core.row.Row;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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

	private static final Logger log = LoggerFactory.getLogger(OnlineService.class);

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
	public String save(Long tableId, Map<String, Object> data) {
		if (data == null) {
			throw new ServiceException("请先填写内容再保存");
		}
		GenTable t = table(tableId);
		List<GenColumn> cols = columns(tableId);
		Set<String> names = physicalNames(t.getTableName());
		String tableName = t.getTableName();
		Object id = data.get("id");
		boolean isNew = id == null || id.toString().isBlank();
		if (isNew) {
			for (GenColumn c : cols) {
				if (!isOne(c.getIsRequired()) || !isOne(c.getIsEdit())) {
					continue;
				}
				Object value = data.get(c.getColumnName());
				if (value == null || value.toString().isBlank()) {
					throw new ServiceException("请填写「" + label(c) + "」。用来在列表里区分这条记录");
				}
			}
		}
		for (GenColumn c : cols) {
			if (!isOne(c.getIsEdit()) || !data.containsKey(c.getColumnName())) {
				continue;
			}
			checkLength(tableName, c, data.get(c.getColumnName()));
		}
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
			writeRow(tableName, () -> Db.insert(tableName, row));
			return "已新增。列表里可以查到这条记录";
		}
		QueryWrapper scope = QueryWrapper.create().from(tableName).where("id = ?", parseId(id));
		applyScope(scope, names);
		if (Db.selectListByQuery(tableName, scope).isEmpty()) {
			throw new ServiceException("这条记录不存在，或已经被删除。请刷新列表后再保存");
		}
		if (names.contains("update_time")) {
			row.set("update_time", LocalDateTime.now());
		}
		writeRow(tableName, () -> Db.updateById(tableName, row));
		return "已保存。列表会显示最新内容";
	}

	private void writeRow(String tableName, Runnable action) {
		try {
			action.run();
		} catch (ServiceException e) {
			throw e;
		} catch (RuntimeException e) {
			log.warn("在线表单写入失败 table={} err={}", tableName, e.getClass().getSimpleName());
			throw new ServiceException("这次没有保存成功。请检查必填项和字数后再试");
		}
	}

	/** 运行时删除：有 is_deleted 列走逻辑删除，否则物理删除（均附加租户范围，防跨租户删） */
	public String remove(Long tableId, List<Long> ids) {
		if (ids == null || ids.isEmpty()) {
			throw new ServiceException("请先选择要删除的记录");
		}
		if (ids.stream().anyMatch(id -> id == null)) {
			throw new ServiceException("有记录不存在。请刷新列表后再删除");
		}
		List<Long> distinct = ids.stream().distinct().toList();
		GenTable t = table(tableId);
		Set<String> names = physicalNames(t.getTableName());
		String tableName = t.getTableName();
		String placeholders = distinct.stream().map(i -> "?").collect(Collectors.joining(","));
		QueryWrapper found = QueryWrapper.create().from(tableName);
		found.and("id IN (" + placeholders + ")", distinct.toArray());
		applyScope(found, names);
		if (Db.selectListByQuery(tableName, found).size() != distinct.size()) {
			throw new ServiceException("有记录不存在，或已经被删除。请刷新列表后再试");
		}
		List<Object> params = new ArrayList<>(distinct);
		String tenantClause = "";
		if (names.contains("tenant_id") && TenantContext.current() != null) {
			tenantClause = " AND tenant_id = ?";
			params.add(TenantContext.current());
		}
		if (!names.contains("is_deleted")) {
			Db.updateBySql("DELETE FROM " + tableName + " WHERE id IN (" + placeholders + ")" + tenantClause,
				params.toArray());
		} else {
			Db.updateBySql("UPDATE " + tableName + " SET is_deleted = 1 WHERE id IN (" + placeholders + ")" + tenantClause,
				params.toArray());
		}
		return "已删除 " + distinct.size() + " 条记录。列表里不会再出现";
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
			throw new ServiceException("这个在线表单不存在。请刷新表单列表后再打开");
		}
		if (!com.mugsun.boot.gen.GenNaming.isIdentifier(t.getTableName())) {
			throw new ServiceException("表名不合法。请回到代码生成里重新导入这张表");
		}
		guardRuntimeTable(t.getTableName());
		return t;
	}

	/** 运行时黑名单：平台系统表前缀 + 元数据表本体（gen_table/gen_column） */
	private static final java.util.Set<String> RUNTIME_PROTECTED_PREFIXES = java.util.Set.of(
		"sys_", "flow_", "flyway_", "qrtz_", "act_", "biz_", "help_", "viz_", "ai_");

	private void guardRuntimeTable(String tableName) {
		String low = tableName.toLowerCase();
		if ("gen_table".equals(low) || "gen_column".equals(low)) {
			throw new ServiceException("元数据表不能当作在线表单。请换一张业务表");
		}
		for (String p : RUNTIME_PROTECTED_PREFIXES) {
			if (low.startsWith(p)) {
				throw new ServiceException("平台系统表不能当作在线表单。请换一张业务表");
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
			throw new ServiceException("读取表结构失败。请确认这张表还在库里，或回到代码生成重新导入");
		}
		if (names.isEmpty()) {
			throw new ServiceException("库里已经没有「" + tableName + "」。请确认表还在，或先删掉这条生成配置");
		}
		return names;
	}

	/** id 解析（非法 id 统一参数错误，防 NumberFormatException 落 500） */
	private Long parseId(Object id) {
		try {
			return Long.valueOf(id.toString());
		} catch (NumberFormatException e) {
			throw new ServiceException("记录编号不对。请刷新列表后再操作");
		}
	}

	private String label(GenColumn column) {
		if (column.getColumnComment() != null && !column.getColumnComment().isBlank()) {
			return column.getColumnComment();
		}
		return column.getColumnName();
	}

	private void checkLength(String tableName, GenColumn column, Object value) {
		if (value == null) {
			return;
		}
		Integer limit = charLimit(tableName, column);
		if (limit == null) {
			return;
		}
		if (value.toString().length() > limit) {
			throw new ServiceException("「" + label(column) + "」请控制在 " + limit + " 个字以内");
		}
	}

	private Integer charLimit(String tableName, GenColumn column) {
		if (column.getColumnType() != null) {
			java.util.regex.Matcher matcher = java.util.regex.Pattern
				.compile("(?i)(?:character\\s+varying|varchar|char)\\s*\\(\\s*(\\d+)\\s*\\)")
				.matcher(column.getColumnType());
			if (matcher.find()) {
				return Integer.parseInt(matcher.group(1));
			}
		}
		try (java.sql.Connection connection = dataSource.getConnection()) {
			java.sql.DatabaseMetaData meta = connection.getMetaData();
			for (String table : List.of(tableName, tableName.toLowerCase(), tableName.toUpperCase())) {
				try (java.sql.ResultSet rs = meta.getColumns(connection.getCatalog(), connection.getSchema(), table,
					column.getColumnName())) {
					if (rs.next()) {
						String typeName = rs.getString("TYPE_NAME");
						int size = rs.getInt("COLUMN_SIZE");
						if (typeName != null && typeName.toLowerCase().contains("char") && size > 0 && size <= 4000) {
							return size;
						}
					}
				}
			}
		} catch (java.sql.SQLException e) {
			log.warn("读取字段长度失败 table={} err={}", tableName, e.getClass().getSimpleName());
		}
		return null;
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
