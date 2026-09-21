package com.mugsun.boot.gen;

import com.mugsun.boot.gen.entity.GenColumn;
import com.mugsun.boot.gen.entity.GenTable;
import com.mugsun.boot.gen.mapper.GenColumnMapper;
import com.mugsun.boot.gen.mapper.GenTableMapper;
import com.mybatisflex.codegen.Generator;
import com.mybatisflex.codegen.config.GlobalConfig;
import com.mybatisflex.codegen.entity.Column;
import com.mybatisflex.codegen.entity.Table;
import com.mybatisflex.core.query.QueryWrapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.sql.DataSource;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 代码生成元数据服务：库表内省导入为 gen_table + gen_column（含类型/控件/命名/查询方式智能推断），
 * 及元数据配置的读取与在线保存。生成产物读此元数据，字段级配置在此持久化。
 */
@Service
public class GenMetaService {

	/** 不进列表展示的系统/审计字段（按 Java 字段名） */
	private static final Set<String> LIST_SKIP = Set.of("id", "tenantId", "isDeleted", "createUser", "updateUser", "updateTime");
	/** 不进新增/编辑表单的系统/审计字段 */
	private static final Set<String> FORM_SKIP = Set.of("id", "tenantId", "isDeleted", "createUser", "updateUser", "createTime", "updateTime");

	private final DataSource dataSource;
	private final GenTableMapper tableMapper;
	private final GenColumnMapper columnMapper;

	public GenMetaService(DataSource dataSource, GenTableMapper tableMapper, GenColumnMapper columnMapper) {
		this.dataSource = dataSource;
		this.tableMapper = tableMapper;
		this.columnMapper = columnMapper;
	}

	/** 导入库表为元数据配置：内省列 → 推断默认 → 落 gen_table + gen_column（已存在则重建列，表级配置沿用/更新） */
	@Transactional(rollbackFor = Exception.class)
	public Long importTable(String tableName, String moduleName, String basePackage, String tablePrefix, String author) {
		// 保护表黑名单：sys_/flow_/biz_ 等平台表禁入低代码通道（在线表单/DDL 消费链信任 gen 元数据）
		com.mugsun.boot.gen.DdlService.guardNotProtected(tableName);
		Table meta = introspect(tableName);
		if (meta == null) {
			throw new IllegalArgumentException("表不存在：" + tableName);
		}
		String prefix = tablePrefix == null ? "" : tablePrefix.trim();
		String stripped = GenNaming.stripPrefix(tableName, prefix);
		GenTable existing = tableMapper.selectOneByQuery(QueryWrapper.create().eq("table_name", tableName));
		GenTable table = existing == null ? new GenTable() : existing;
		table.setTableName(tableName);
		table.setTableComment(meta.getComment());
		table.setEntityName(GenNaming.toCamel(stripped, true));
		table.setModuleName(moduleName == null || moduleName.isBlank() ? "system" : moduleName.trim());
		table.setBusinessName(GenNaming.toBusinessName(stripped));
		table.setFunctionName(meta.getComment() == null || meta.getComment().isBlank()
			? GenNaming.toCamel(stripped, true) : meta.getComment());
		table.setFunctionAuthor(author == null || author.isBlank() ? "mugsun" : author.trim());
		table.setBasePackage(basePackage == null || basePackage.isBlank() ? "com.mugsun.boot" : basePackage.trim());
		table.setTablePrefix(prefix);
		if (table.getGenType() == null) {
			table.setGenType("zip");
		}
		if (table.getTplCategory() == null) {
			table.setTplCategory("crud");
		}
		// 自动识别树表父级字段（存在 parentId 属性则记录，供切换为树表模板）
		if (table.getTreeParentField() == null) {
			for (Column c : meta.getColumns()) {
				if ("parentId".equals(c.getProperty())) {
					table.setTreeParentField(c.getName());
					break;
				}
			}
		}
		if (existing == null) {
			tableMapper.insertSelective(table);
		} else {
			tableMapper.update(table);
		}

		// 合并字段配置：新增列补默认、已有列保留用户配置只刷新结构、删除列剔除（增量同步保留已编辑配置）
		mergeColumns(table.getId(), meta.getColumns());
		return table.getId();
	}

	/** 增量同步：按当前库表结构合并字段配置，保留表级 + 字段级已编辑配置 */
	@Transactional(rollbackFor = Exception.class)
	public void syncTable(Long tableId) {
		GenTable table = tableMapper.selectOneById(tableId);
		if (table == null) {
			throw new IllegalArgumentException("表配置不存在：" + tableId);
		}
		Table meta = introspect(table.getTableName());
		if (meta == null) {
			throw new IllegalArgumentException("表不存在：" + table.getTableName());
		}
		mergeColumns(tableId, meta.getColumns());
	}

	/**
	 * 合并库表列到 gen_column：
	 * <ul><li>已有列（按列名匹配）：仅刷新结构派生字段（列类型/Java 类型），保留用户配置（控件/字典/查询项/列表项等）</li>
	 * <li>新增列：按推断落默认配置，追加排序</li>
	 * <li>库中已删的列：从 gen_column 剔除</li></ul>
	 */
	private void mergeColumns(Long tableId, List<Column> metaColumns) {
		List<GenColumn> existing = columnMapper.selectListByQuery(QueryWrapper.create().eq("table_id", tableId));
		Map<String, GenColumn> byName = new HashMap<>();
		for (GenColumn c : existing) {
			byName.put(c.getColumnName(), c);
		}
		Set<String> seen = new HashSet<>();
		int maxSort = existing.stream().mapToInt(c -> c.getSort() == null ? 0 : c.getSort()).max().orElse(0);
		for (Column mc : metaColumns) {
			seen.add(mc.getName());
			GenColumn ex = byName.get(mc.getName());
			if (ex != null) {
				// 保留用户配置，只刷新结构派生
				ex.setColumnType(mc.getRawType());
				ex.setJavaType(mc.getPropertySimpleType());
				columnMapper.update(ex);
			} else {
				columnMapper.insert(buildColumn(tableId, mc, ++maxSort));
			}
		}
		for (GenColumn ec : existing) {
			if (!seen.contains(ec.getColumnName())) {
				columnMapper.deleteById(ec.getId());
			}
		}
	}

	/** 由内省列推断字段配置 */
	private GenColumn buildColumn(Long tableId, Column c, int sort) {
		String field = c.getProperty();
		String javaType = c.getPropertySimpleType();
		boolean pk = "id".equals(field);
		GenColumn col = new GenColumn();
		col.setTableId(tableId);
		col.setColumnName(c.getName());
		col.setColumnComment(c.getComment());
		col.setColumnType(c.getRawType());
		col.setJavaType(javaType);
		col.setJavaField(field);
		col.setIsPk(pk ? 1 : 0);
		col.setIsIncrement(Boolean.TRUE.equals(c.getAutoIncrement()) ? 1 : 0);
		boolean notNull = c.getNullable() != null && c.getNullable() == 0;
		col.setIsRequired(!pk && notNull && !FORM_SKIP.contains(field) ? 1 : 0);
		col.setIsInsert(FORM_SKIP.contains(field) ? 0 : 1);
		col.setIsEdit(FORM_SKIP.contains(field) || pk ? 0 : 1);
		col.setIsList(LIST_SKIP.contains(field) ? 0 : 1);
		col.setIsQuery(0);
		col.setQueryType("String".equals(javaType) ? "LIKE" : "EQ");
		col.setHtmlType(inferHtmlType(field, javaType));
		col.setSort(sort);
		return col;
	}

	/** 控件类型推断：日期→datetime、长文本→textarea、状态/类型→select、布尔→switch、数值→number、其余 input */
	private String inferHtmlType(String field, String javaType) {
		String jt = javaType == null ? "" : javaType;
		String f = field == null ? "" : field.toLowerCase();
		if (jt.equals("LocalDateTime") || jt.equals("LocalDate") || jt.equals("Date") || jt.equals("Timestamp")) {
			return "datetime";
		}
		if (f.matches(".*(remark|content|description|intro|detail).*")) {
			return "textarea";
		}
		if (f.matches(".*(status|type|state|level|sex|gender|kind|category).*")) {
			return "select";
		}
		if (jt.equals("Boolean") || f.startsWith("is") || f.startsWith("has") || f.startsWith("enabled")) {
			return "switch";
		}
		if (jt.equals("Integer") || jt.equals("Long") || jt.equals("BigDecimal") || jt.equals("Double")
			|| jt.equals("Float") || jt.equals("Short")) {
			return "number";
		}
		return "input";
	}

	/** 已导入的表配置列表 */
	public List<GenTable> list() {
		return tableMapper.selectListByQuery(QueryWrapper.create().orderBy("id", false));
	}

	/** 读取某表的元数据配置（表 + 字段，字段按 sort 升序）；表不存在返空（防 NPE 500） */
	public Map<String, Object> config(Long tableId) {
		GenTable table = tableMapper.selectOneById(tableId);
		if (table == null) {
			throw new com.mugsun.core.tool.exception.ServiceException("表配置不存在");
		}
		List<GenColumn> columns = columnMapper.selectListByQuery(
			QueryWrapper.create().eq("table_id", tableId).orderBy("sort", true));
		return Map.of("table", table, "columns", columns);
	}

	/** 保存表级 + 字段级在线配置（字段 upsert：无 id 新增列、有 id 更新，支撑动态建表加字段/改名） */
	@Transactional(rollbackFor = Exception.class)
	public void saveConfig(GenTable table, List<GenColumn> columns) {
		if (table == null || table.getId() == null) {
			throw new com.mugsun.core.tool.exception.ServiceException("表配置不存在");
		}
		// 元数据是三条消费链（在线表单/DDL/代码渲染）的信任根：表名/包名/模块名等全部强校验，防投毒
		validateTableMeta(table);
		validateColumnNames(columns);
		tableMapper.update(table);
		if (columns != null) {
			for (GenColumn col : columns) {
				if (col.getId() == null) {
					col.setTableId(table.getId());
					applyColumnDefaults(col);
					columnMapper.insert(col);
				} else {
					columnMapper.update(col);
				}
			}
		}
	}

	/** 表级元数据白名单校验：表名标识符+非保护表、包名/模块名/业务名/作者/实体名格式（产物路径与代码注入防线） */
	private void validateTableMeta(GenTable table) {
		if (!GenNaming.isIdentifier(table.getTableName())) {
			throw new com.mugsun.core.tool.exception.ServiceException("非法表名：" + table.getTableName());
		}
		com.mugsun.boot.gen.DdlService.guardNotProtected(table.getTableName());
		if (table.getBasePackage() != null && !table.getBasePackage().isBlank()
			&& !table.getBasePackage().matches("^[a-z][a-z0-9]*(\\.[a-z][a-z0-9]*){1,6}$")) {
			throw new com.mugsun.core.tool.exception.ServiceException("非法基础包名：" + table.getBasePackage());
		}
		if (table.getModuleName() != null && !table.getModuleName().isBlank()
			&& !table.getModuleName().matches("^[a-z][a-z0-9-]{0,30}$")) {
			throw new com.mugsun.core.tool.exception.ServiceException("非法模块名：" + table.getModuleName());
		}
		if (table.getBusinessName() != null && !table.getBusinessName().isBlank()
			&& !table.getBusinessName().matches("^[a-z][a-z0-9]{0,30}$")) {
			throw new com.mugsun.core.tool.exception.ServiceException("非法业务名：" + table.getBusinessName());
		}
		if (table.getEntityName() != null && !table.getEntityName().isBlank()
			&& !table.getEntityName().matches("^[A-Z][A-Za-z0-9]{0,60}$")) {
			throw new com.mugsun.core.tool.exception.ServiceException("非法实体名：" + table.getEntityName());
		}
	}

	/** 持久化候选元数据为新表配置（AI/手工正向建表通道，不建物理表），返回表配置 id */
	@Transactional(rollbackFor = Exception.class)
	public Long createDraft(GenTable table, List<GenColumn> columns) {
		if (table == null || table.getTableName() == null || table.getTableName().isBlank()) {
			throw new com.mugsun.core.tool.exception.ServiceException("表名不能为空");
		}
		if (!GenNaming.isIdentifier(table.getTableName())) {
			throw new com.mugsun.core.tool.exception.ServiceException("非法表名：" + table.getTableName());
		}
		validateColumnNames(columns);
		GenTable dup = tableMapper.selectOneByQuery(QueryWrapper.create().eq("table_name", table.getTableName()));
		if (dup != null) {
			throw new com.mugsun.core.tool.exception.ServiceException("表配置已存在：" + table.getTableName());
		}
		if (table.getModuleName() == null || table.getModuleName().isBlank()) {
			table.setModuleName("system");
		}
		if (table.getBasePackage() == null || table.getBasePackage().isBlank()) {
			table.setBasePackage("com.mugsun.boot");
		}
		if (table.getFunctionAuthor() == null || table.getFunctionAuthor().isBlank()) {
			table.setFunctionAuthor("mugsun");
		}
		if (table.getGenType() == null) {
			table.setGenType("zip");
		}
		if (table.getTplCategory() == null) {
			table.setTplCategory("crud");
		}
		table.setId(null);
		tableMapper.insertSelective(table);
		if (columns != null) {
			int sort = 0;
			for (GenColumn c : columns) {
				c.setId(null);
				c.setTableId(table.getId());
				if (c.getSort() == null) {
					c.setSort(++sort);
				} else {
					sort = c.getSort();
				}
				applyColumnDefaults(c);
				columnMapper.insert(c);
			}
		}
		return table.getId();
	}

	/** 校验列名/旧列名为合法标识符，杜绝非法名落入元数据（防 DDL 注入 + 无效配置残留）；
	 *  javaField/dictType 同为渲染产物注入面，按格式白名单校验 */
	private void validateColumnNames(List<GenColumn> columns) {
		if (columns == null) {
			return;
		}
		for (GenColumn c : columns) {
			if (!GenNaming.isIdentifier(c.getColumnName())) {
				throw new com.mugsun.core.tool.exception.ServiceException("非法列名：" + c.getColumnName());
			}
			if (c.getColumnNameOld() != null && !c.getColumnNameOld().isBlank()
				&& !GenNaming.isIdentifier(c.getColumnNameOld())) {
				throw new com.mugsun.core.tool.exception.ServiceException("非法旧列名：" + c.getColumnNameOld());
			}
			if (c.getJavaField() != null && !c.getJavaField().isBlank()
				&& !c.getJavaField().matches("^[a-z][A-Za-z0-9]{0,60}$")) {
				throw new com.mugsun.core.tool.exception.ServiceException("非法 Java 字段名：" + c.getJavaField());
			}
			if (c.getDictType() != null && !c.getDictType().isBlank()
				&& !c.getDictType().matches("^[a-z][a-z0-9_]{0,60}$")) {
				throw new com.mugsun.core.tool.exception.ServiceException("非法字典码：" + c.getDictType());
			}
		}
	}

	/** 补齐 gen_column 非空默认，容忍前端传入的部分字段（新增列常缺 is_increment/is_required 等 NOT NULL 列） */
	private void applyColumnDefaults(GenColumn c) {
		if (c.getJavaType() == null || c.getJavaType().isBlank()) {
			c.setJavaType("String");
		}
		if (c.getJavaField() == null || c.getJavaField().isBlank()) {
			c.setJavaField(GenNaming.toCamel(c.getColumnName() == null ? "" : c.getColumnName(), false));
		}
		if (c.getIsPk() == null) {
			c.setIsPk(0);
		}
		if (c.getIsIncrement() == null) {
			c.setIsIncrement(0);
		}
		if (c.getIsRequired() == null) {
			c.setIsRequired(0);
		}
		if (c.getIsInsert() == null) {
			c.setIsInsert(1);
		}
		if (c.getIsEdit() == null) {
			c.setIsEdit(1);
		}
		if (c.getIsList() == null) {
			c.setIsList(1);
		}
		if (c.getIsQuery() == null) {
			c.setIsQuery(0);
		}
		if (c.getQueryType() == null || c.getQueryType().isBlank()) {
			c.setQueryType("String".equals(c.getJavaType()) ? "LIKE" : "EQ");
		}
		if (c.getHtmlType() == null || c.getHtmlType().isBlank()) {
			c.setHtmlType("input");
		}
		if (c.getSort() == null) {
			c.setSort(0);
		}
	}

	/** 内省单表元数据（复用 Flex codegen 逆向） */
	private Table introspect(String tableName) {
		GlobalConfig config = new GlobalConfig();
		config.getPackageConfig().setBasePackage("com.mugsun.preview");
		config.getStrategyConfig().setGenerateTable(tableName);
		for (Table t : new Generator(dataSource, config).getTables()) {
			if (t.getName().equalsIgnoreCase(tableName)) {
				return t;
			}
		}
		return null;
	}
}
