package com.mugsun.boot.gen;

import com.mugsun.boot.gen.entity.GenColumn;
import com.mugsun.boot.gen.entity.GenTable;
import com.mugsun.boot.gen.mapper.GenColumnMapper;
import com.mugsun.boot.gen.mapper.GenTableMapper;
import com.mugsun.core.tool.exception.ServiceException;
import com.mybatisflex.core.query.QueryWrapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 动态建表引擎：以 gen_table/gen_column 元数据为源，向真实数据库生成并执行 DDL。
 * <ul><li>正向建表：config → CREATE TABLE（雪花主键 + 审计列，平台规约）</li>
 * <li>增量同步：diff 物理表结构 → ADD COLUMN（新增字段）/ RENAME COLUMN（改名保数据），不 DROP</li>
 * <li>强制重建：DROP + CREATE（毁数据，需显式确认，且禁作用于系统表）</li></ul>
 * 安全：标识符白名单校验、仅作用于 gen 托管表、系统表前缀保护、DDL 事务化执行。
 */
@Service
public class DdlService {

	/** 受保护的系统表前缀（不可重建/删除，且新建不得占用）；biz_/help_/viz_/ai_ 为平台既有业务域，同样禁碰。
	 *  gen_table/gen_column 两张元数据表按精确名单保护（低代码业务表常以 gen_ 命名，不可按前缀误伤） */
	private static final Set<String> PROTECTED = Set.of("sys_", "flyway_", "flow_", "qrtz_", "blade_", "act_",
		"biz_", "help_", "viz_", "ai_");

	/** 精确保护名单：代码生成元数据表本体 */
	private static final Set<String> PROTECTED_EXACT = Set.of("gen_table", "gen_column");
	/** 审计列由引擎统一维护，建表时显式补齐，不随业务列重复渲染 */
	private static final Set<String> AUDIT = Set.of("id", "create_time", "update_time", "is_deleted");

	private final DataSource dataSource;
	private final GenTableMapper tableMapper;
	private final GenColumnMapper columnMapper;

	/** 金仓等：业务 schema；亦用于 information_schema 过滤（避免 JDBC getSchema→坏掉的 pg_catalog） */
	@Value("${mugsun.db.default-schema:}")
	private String defaultDbSchema;

	public DdlService(DataSource dataSource, GenTableMapper tableMapper, GenColumnMapper columnMapper) {
		this.dataSource = dataSource;
		this.tableMapper = tableMapper;
		this.columnMapper = columnMapper;
	}

	/** 预览 DDL（不执行）：物理表不存在→建表；存在→同步（force 走 DROP+CREATE，否则增量 ADD/RENAME） */
	public List<String> preview(Long tableId, boolean force) {
		GenTable t = table(tableId);
		SqlDialect d = dialect();
		if (!force && !physicalExists(validName(t.getTableName()))) {
			return List.of(buildCreate(t, columns(tableId), d));
		}
		return buildSync(t, columns(tableId), force, d);
	}

	/** 校验表名合法且非系统保护表（确认建模前置校验，避免落入无法建表的无效/越权配置） */
	public void validateTableName(String tableName) {
		guardNotProtected(validName(tableName));
	}

	/** 正向建表：物理表不存在时按元数据 CREATE */
	@Transactional(rollbackFor = Exception.class)
	public void createTable(Long tableId) {
		GenTable t = table(tableId);
		String tn = validName(t.getTableName());
		guardNotProtected(tn);
		if (physicalExists(tn)) {
			throw new ServiceException("物理表已存在，请改用同步：" + tn);
		}
		execute(List.of(buildCreate(t, columns(tableId), dialect())));
	}

	/** 增量/强制同步：执行 DDL，并在改名完成后清空 column_name_old；force 重建毁数据，仅平台超管。无增量变更时返回提示文案（非异常）。 */
	@Transactional(rollbackFor = Exception.class)
	public String syncTable(Long tableId, boolean force) {
		GenTable t = table(tableId);
		String tn = validName(t.getTableName());
		guardNotProtected(tn);
		if (force && !com.mugsun.boot.tenant.TenantContext.isPlatformSuperAdmin()) {
			throw new ServiceException("强制重建将清空数据，仅平台超管可执行");
		}
		List<GenColumn> cols = columns(tableId);
		SqlDialect d = dialect();
		if (force) {
			execute(buildSync(t, cols, true, d));
			return "重建成功";
		}
		if (!physicalExists(tn)) {
			throw new ServiceException("物理表不存在，请先建表：" + tn);
		}
		List<String> stmts = buildSync(t, cols, false, d);
		if (stmts.isEmpty()) {
			return "无结构变更";
		}
		execute(stmts);
		// 改名已落库，清除追踪标记避免二次 RENAME
		for (GenColumn c : cols) {
			if (c.getColumnNameOld() != null && !c.getColumnNameOld().isBlank()) {
				c.setColumnNameOld(null);
				columnMapper.update(c, false);
			}
		}
		return "同步成功";
	}

	// ==================== DDL 构建 ====================

	private String buildCreate(GenTable t, List<GenColumn> cols, SqlDialect d) {
		String tn = validName(t.getTableName());
		List<String> lines = new ArrayList<>();
		lines.add("id " + d.bigintType() + " PRIMARY KEY");
		for (GenColumn c : cols) {
			String cn = c.getColumnName();
			if (cn == null || AUDIT.contains(cn)) {
				continue;
			}
			validName(cn);
			String nn = isOne(c.getIsRequired()) ? " NOT NULL" : "";
			lines.add(cn + " " + sqlType(c, d) + nn);
		}
		// 租户隔离列（平台铁律）：低代码建表默认携带，在线表单按物理列强制过滤（可空 = 平台共享语义仅平台超管写入）
		lines.add("tenant_id " + (d == SqlDialect.ORACLE ? "VARCHAR2(12)" : "VARCHAR(12)"));
		lines.add("create_time " + d.timestampType());
		lines.add("update_time " + d.timestampType());
		lines.add("is_deleted " + d.intType() + d.notNullDefaultZero());
		return "CREATE TABLE " + tn + " (\n\t" + String.join(",\n\t", lines) + "\n)";
	}

	private List<String> buildSync(GenTable t, List<GenColumn> cols, boolean force, SqlDialect d) {
		String tn = validName(t.getTableName());
		if (force) {
			return List.of(d.dropTable(tn), buildCreate(t, cols, d));
		}
		Map<String, String> physical = physicalColumns(tn);
		Set<String> physSet = physical.keySet();
		List<String> stmts = new ArrayList<>();
		for (GenColumn c : cols) {
			String cn = c.getColumnName();
			if (cn == null || "id".equals(cn)) {
				continue;
			}
			validName(cn);
			String old = c.getColumnNameOld();
			boolean hasNew = physSet.contains(cn.toLowerCase());
			if (old != null && !old.isBlank() && physSet.contains(old.toLowerCase()) && !hasNew) {
				stmts.add("ALTER TABLE " + tn + " RENAME COLUMN " + validName(old) + " TO " + cn);
			} else if (!hasNew) {
				// 新增列须可空（存量行无值），is_deleted 例外给默认值（Oracle 系 DEFAULT 在 NOT NULL 前）
				String def = "is_deleted".equals(cn) ? d.notNullDefaultZero() : "";
				stmts.add("ALTER TABLE " + tn + " ADD COLUMN " + cn + " " + sqlType(c, d) + def);
			}
		}
		return stmts;
	}

	/** Java 类型 → 目标库 SQL 类型（长文本控件落大字段），差异全部收敛在 {@link SqlDialect} */
	private String sqlType(GenColumn c, SqlDialect d) {
		return d.columnType(c.getJavaType(), "textarea".equals(c.getHtmlType()));
	}

	// ==================== 执行 / 内省 ====================

	/** DDL 逐条执行，整体回滚（PG/金仓/MySQL 事务安全；Oracle 系/达梦 DDL 隐式提交，回滚仅覆盖未提交语句） */
	private void execute(List<String> stmts) {
		try (Connection c = dataSource.getConnection()) {
			boolean auto = c.getAutoCommit();
			c.setAutoCommit(false);
			try (Statement st = c.createStatement()) {
				for (String s : stmts) {
					st.execute(s);
				}
				c.commit();
			} catch (SQLException e) {
				c.rollback();
				throw new ServiceException("DDL 执行失败：" + e.getMessage());
			} finally {
				c.setAutoCommit(auto);
			}
		} catch (SQLException e) {
			throw new ServiceException("获取连接失败：" + e.getMessage());
		}
	}

	private boolean physicalExists(String tn) {
		return !physicalColumns(tn).isEmpty();
	}

	/**
	 * 物理表现有列。
	 * PG 系（含金仓）：只用 information_schema；空结果=表不存在，绝不走 JDBC getColumns
	 * （社区金仓缺/残 pg_catalog，getColumns 会报 c.oid / pg_namespace 并弄脏事务）。
	 * Oracle/MySQL 系：仍走 DatabaseMetaData。
	 */
	private Map<String, String> physicalColumns(String tn) {
		Map<String, String> m = new LinkedHashMap<>();
		try (Connection c = dataSource.getConnection()) {
			SqlDialect d = dialect();
			if (d == SqlDialect.POSTGRES) {
				collectColumnsViaInformationSchema(c, tn, m);
				return m;
			}
			DatabaseMetaData md = c.getMetaData();
			collectColumns(md, c, tn.toLowerCase(), m);
			if (m.isEmpty()) {
				collectColumns(md, c, tn.toUpperCase(), m);
			}
		} catch (SQLException e) {
			throw new ServiceException("读取表结构失败：" + e.getMessage());
		}
		return m;
	}

	private void collectColumns(DatabaseMetaData md, Connection c, String tn, Map<String, String> out) throws SQLException {
		try (ResultSet rs = md.getColumns(c.getCatalog(), c.getSchema(), tn, null)) {
			while (rs.next()) {
				out.put(rs.getString("COLUMN_NAME").toLowerCase(), rs.getString("TYPE_NAME"));
			}
		}
	}

	/**
	 * PG 系列内省：information_schema（不碰 JDBC MetaData / pg_catalog）。
	 * schema：配置 mugsun.db.default-schema → SQL current_schema()（勿用 Connection#getSchema，金仓会炸）。
	 */
	private void collectColumnsViaInformationSchema(Connection c, String tn, Map<String, String> out) throws SQLException {
		String sql = "SELECT column_name, data_type FROM information_schema.columns "
			+ "WHERE lower(table_name) = lower(?) "
			+ "AND table_schema = coalesce(nullif(?, ''), current_schema()) "
			+ "ORDER BY ordinal_position";
		String schema = StringUtils.hasText(defaultDbSchema) ? defaultDbSchema : "";
		try (PreparedStatement ps = c.prepareStatement(sql)) {
			ps.setString(1, tn);
			ps.setString(2, schema);
			try (ResultSet rs = ps.executeQuery()) {
				while (rs.next()) {
					out.put(rs.getString(1).toLowerCase(), rs.getString(2));
				}
			}
		}
	}

	/** 目标库方言族：以 Flex {@code DbTypeUtil} 探测的 DbType 归族（PG/Oracle/MySQL 系），失败回退平台主库族 PG。 */
	private SqlDialect dialect() {
		return DbDialects.of(dataSource);
	}

	// ==================== 安全 / 取数 ====================

	private String validName(String name) {
		if (!GenNaming.isIdentifier(name)) {
			throw new ServiceException("非法标识符：" + name);
		}
		return name;
	}

	/** 表名保护校验（public：元数据写入侧复用——gen 元数据是 在线表单/DDL/渲染 三链信任根，受保护表一律拒） */
	public static void guardNotProtected(String tn) {
		String low = tn.toLowerCase();
		if (PROTECTED_EXACT.contains(low)) {
			throw new ServiceException("受保护的系统表不可建/重建：" + tn);
		}
		for (String p : PROTECTED) {
			if (low.startsWith(p)) {
				throw new ServiceException("受保护的系统表不可建/重建：" + tn);
			}
		}
	}

	private GenTable table(Long tableId) {
		GenTable t = tableMapper.selectOneById(tableId);
		if (t == null) {
			throw new ServiceException("表配置不存在：" + tableId);
		}
		return t;
	}

	private List<GenColumn> columns(Long tableId) {
		return columnMapper.selectListByQuery(
			QueryWrapper.create().eq("table_id", tableId).orderBy("sort", true));
	}

	private boolean isOne(Integer v) {
		return v != null && v == 1;
	}
}
