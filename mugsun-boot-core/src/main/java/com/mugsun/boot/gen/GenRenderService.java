package com.mugsun.boot.gen;

import com.jfinal.template.Engine;
import com.mugsun.boot.gen.entity.GenColumn;
import com.mugsun.boot.gen.entity.GenTable;
import com.mugsun.boot.gen.mapper.GenColumnMapper;
import com.mugsun.boot.gen.mapper.GenTableMapper;
import com.mybatisflex.codegen.Generator;
import com.mybatisflex.codegen.config.GlobalConfig;
import com.mybatisflex.codegen.entity.Column;
import com.mybatisflex.codegen.entity.Table;
import com.mybatisflex.core.query.QueryWrapper;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

import javax.sql.DataSource;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * 代码生成渲染：读 gen_table + gen_column 元数据 → 构建数据模型 → jfinal Enjoy 渲染自定义模板，
 * 产出对齐平台规约的全栈代码（后端 Entity/Mapper/Controller + 前端 列表页/api/类型 + 菜单 SQL）。
 * 渲染到字符串，供预览（类别→内容）/ zip（路径→内容，解压即入工程）统一复用。
 */
@Service
public class GenRenderService {

	/** BaseEntity 继承字段，实体不重复声明 */
	private static final Set<String> BASE_FIELDS = Set.of("id", "createTime", "updateTime", "isDeleted", "tenantId");
	/** 需显式 import 的 Java 类型 */
	private static final Map<String, String> TYPE_IMPORTS = Map.of(
		"BigDecimal", "java.math.BigDecimal",
		"LocalDateTime", "java.time.LocalDateTime",
		"LocalDate", "java.time.LocalDate",
		"LocalTime", "java.time.LocalTime");

	private final GenTableMapper tableMapper;
	private final GenColumnMapper columnMapper;
	private final DataSource dataSource;
	private final Engine engine = Engine.use();

	public GenRenderService(GenTableMapper tableMapper, GenColumnMapper columnMapper, DataSource dataSource) {
		this.tableMapper = tableMapper;
		this.columnMapper = columnMapper;
		this.dataSource = dataSource;
	}

	/** 单个产物：类别（预览分组）、工程相对路径（解压即入）、内容 */
	public record Product(String category, String path, String content) {
	}

	/** 生成某表全部全栈产物（从库读元数据） */
	public List<Product> generate(Long tableId) {
		GenTable t = tableMapper.selectOneById(tableId);
		if (t == null) {
			throw new IllegalArgumentException("表配置不存在：" + tableId);
		}
		List<GenColumn> cols = columnMapper.selectListByQuery(
			QueryWrapper.create().eq("table_id", tableId).orderBy("sort", true));
		return generate(t, cols);
	}

	/** 生成某表全部全栈产物（从给定元数据；主子表时内省子表列） */
	public List<Product> generate(GenTable t, List<GenColumn> cols) {
		List<GenColumn> childCols = "master".equals(nvl(t.getTplCategory(), "crud")) && t.getSubTableName() != null
			? introspectColumns(t.getSubTableName())
			: List.of();
		return generate(t, cols, childCols);
	}

	/** 生成全栈产物（显式子表列，供 golden 免库测试） */
	@SuppressWarnings("unchecked")
	public List<Product> generate(GenTable t, List<GenColumn> cols, List<GenColumn> childCols) {
		Map<String, Object> m = buildModel(t, cols, childCols);
		String javaBase = String.valueOf(m.get("basePackage")).replace('.', '/');
		String module = String.valueOf(m.get("module"));
		String entity = String.valueOf(m.get("entityName"));
		String kebab = String.valueOf(m.get("businessKebab"));
		String tableName = String.valueOf(m.get("tableName"));
		String backend = "mugsun-boot/src/main/java/" + javaBase + "/" + module;
		String viewDir = "mugsun-pc/src/views/" + module + "/" + kebab;
		String apiDir = "mugsun-pc/src/api/" + module + "/" + kebab;
		boolean isMaster = Boolean.TRUE.equals(m.get("isMaster"));
		boolean isTree = Boolean.TRUE.equals(m.get("isTree"));

		List<Product> list = new ArrayList<>();
		list.add(new Product("entity", backend + "/entity/" + entity + ".java", render("entity.tpl", m)));
		list.add(new Product("mapper", backend + "/mapper/" + entity + "Mapper.java", render("mapper.tpl", m)));
		if (isMaster) {
			String childEntity = String.valueOf(m.get("childEntityName"));
			Map<String, Object> childModel = (Map<String, Object>) m.get("childModel");
			list.add(new Product("childEntity", backend + "/entity/" + childEntity + ".java", render("entity.tpl", childModel)));
			list.add(new Product("childMapper", backend + "/mapper/" + childEntity + "Mapper.java", render("mapper.tpl", childModel)));
			list.add(new Product("service", backend + "/service/" + entity + "Service.java", render("service.tpl", m)));
			list.add(new Product("controller", backend + "/controller/" + entity + "Controller.java", render("master-controller.tpl", m)));
			list.add(new Product("vue", viewDir + "/index.vue", render("master.vue.tpl", m)));
		} else {
			list.add(new Product("controller", backend + "/controller/" + entity + "Controller.java", render("controller.tpl", m)));
			list.add(new Product("vue", viewDir + "/index.vue", render(isTree ? "tree.vue.tpl" : "index.vue.tpl", m)));
		}
		list.add(new Product("api", apiDir + "/index.ts", render("api.ts.tpl", m)));
		list.add(new Product("type", apiDir + "/type.ts", render("type.ts.tpl", m)));
		list.add(new Product("menu", "sql/" + tableName + "_menu.sql", render("menu.sql.tpl", m)));
		return list;
	}

	/** 内省表列为 GenColumn（子表用，仅结构字段） */
	private List<GenColumn> introspectColumns(String tableName) {
		GlobalConfig config = new GlobalConfig();
		config.getPackageConfig().setBasePackage("com.mugsun.preview");
		config.getStrategyConfig().setGenerateTable(tableName);
		List<GenColumn> result = new ArrayList<>();
		for (Table tb : new Generator(dataSource, config).getTables()) {
			if (tb.getName().equalsIgnoreCase(tableName)) {
				int sort = 1;
				for (Column c : tb.getColumns()) {
					GenColumn gc = new GenColumn();
					gc.setColumnName(c.getName());
					gc.setColumnComment(c.getComment());
					gc.setColumnType(c.getRawType());
					gc.setJavaType(c.getPropertySimpleType());
					gc.setJavaField(c.getProperty());
					gc.setSort(sort++);
					result.add(gc);
				}
			}
		}
		return result;
	}

	/** 预览：类别→内容 */
	public Map<String, String> preview(Long tableId) {
		Map<String, String> result = new LinkedHashMap<>();
		for (Product p : generate(tableId)) {
			result.put(p.category(), p.content());
		}
		return result;
	}

	/** 打包 zip：路径→内容，解压即入工程目录；entry 名强制归一化并拒绝穿越（防投毒元数据构造 ../ 写任意文件） */
	public byte[] zip(Long tableId) {
		ByteArrayOutputStream bos = new ByteArrayOutputStream();
		try (ZipOutputStream zos = new ZipOutputStream(bos)) {
			for (Product p : generate(tableId)) {
				zos.putNextEntry(new ZipEntry(safeEntryName(p.path())));
				zos.write(p.content().getBytes(StandardCharsets.UTF_8));
				zos.closeEntry();
			}
		} catch (IOException e) {
			throw new IllegalStateException("打包失败", e);
		}
		return bos.toByteArray();
	}

	/** zip entry 名安全校验：归一化后拒绝对路径/../ 穿越段（zip slip 防线，产物路径由元数据拼接） */
	private String safeEntryName(String path) {
		String normalized = java.nio.file.Paths.get(path).normalize().toString().replace('\\', '/');
		if (normalized.startsWith("/") || normalized.startsWith("..") || normalized.contains("/../")
			|| normalized.matches("^[A-Za-z]:.*")) {
			throw new IllegalStateException("非法产物路径：" + path);
		}
		return normalized;
	}

	/** 由元数据构建 Enjoy 数据模型 */
	Map<String, Object> buildModel(GenTable t, List<GenColumn> cols, List<GenColumn> childCols) {
		String base = nvl(t.getBasePackage(), "com.mugsun.boot");
		String module = nvl(t.getModuleName(), "system");
		String entityName = t.getEntityName();
		String business = t.getBusinessName();
		String kebab = GenNaming.toKebab(business);

		Map<String, Object> m = new LinkedHashMap<>();
		m.put("entityName", entityName);
		m.put("entityVar", uncapitalize(entityName));
		m.put("tableName", t.getTableName());
		m.put("functionName", cleanLabel(nvl(t.getFunctionName(), entityName)));
		m.put("author", nvl(t.getFunctionAuthor(), "mugsun"));
		m.put("module", module);
		m.put("basePackage", base);
		m.put("business", business);
		m.put("businessKebab", kebab);
		m.put("entityPkg", base + "." + module + ".entity");
		m.put("mapperPkg", base + "." + module + ".mapper");
		m.put("controllerPkg", base + "." + module + ".controller");
		m.put("servicePkg", base + "." + module + ".service");
		m.put("mapperName", entityName + "Mapper");
		m.put("mapperVar", uncapitalize(entityName) + "Mapper");
		m.put("path", "/" + module + "/" + kebab);
		m.put("apiUrl", "/api/" + module + "/" + kebab);
		m.put("permPrefix", module + ":" + business);
		// 模板类别：crud / tree（树表 INode + /tree）/ master（主子表一对多）
		String tplCategory = nvl(t.getTplCategory(), "crud");
		boolean isTree = "tree".equals(tplCategory);
		boolean isMaster = "master".equals(tplCategory);
		m.put("tplCategory", tplCategory);
		m.put("isTree", isTree);
		m.put("isMaster", isMaster);
		// 类声明（预拼，规避 Enjoy 内联指令吞空格）
		m.put("classDecl", "extends BaseEntity" + (isTree ? " implements INode<" + entityName + ">" : ""));
		List<Map<String, Object>> entityColumns = new ArrayList<>();
		List<Map<String, Object>> listColumns = new ArrayList<>();
		List<Map<String, Object>> formColumns = new ArrayList<>();
		List<String> imports = new ArrayList<>();
		Set<String> elImports = new LinkedHashSet<>(List.of("ElButton", "ElCard", "ElDialog", "ElForm", "ElFormItem"));
		Set<String> dictCodes = new LinkedHashSet<>();
		// 主子表：子实体/子表/外键 + 复用 entity/mapper 模板的子模型
		if (isMaster && t.getSubTableName() != null) {
			String childEntity = GenNaming.toCamel(GenNaming.stripPrefix(t.getSubTableName(), nvl(t.getTablePrefix(), "")), true);
			String childVar = uncapitalize(childEntity);
			String joinField = GenNaming.toCamel(nvl(t.getSubJoinField(), ""), false);
			m.put("childEntityName", childEntity);
			m.put("childVar", childVar);
			m.put("childMapperName", childEntity + "Mapper");
			m.put("childMapperVar", childVar + "Mapper");
			m.put("subListField", childVar + "List");
			m.put("subListCap", capitalize(childVar + "List"));
			m.put("subJoinField", joinField);
			m.put("subJoinCap", capitalize(joinField));
			m.put("subJoinColumn", nvl(t.getSubJoinField(), ""));
			m.put("childApiUrl", "/api/" + module + "/" + kebab);
			GenTable childTable = new GenTable();
			childTable.setId(t.getId());
			childTable.setTableName(t.getSubTableName());
			childTable.setEntityName(childEntity);
			childTable.setModuleName(module);
			childTable.setBusinessName(childVar);
			childTable.setBasePackage(base);
			childTable.setTablePrefix(t.getTablePrefix());
			childTable.setFunctionName(childEntity);
			childTable.setFunctionAuthor(t.getFunctionAuthor());
			childTable.setTplCategory("crud");
			Map<String, Object> childModel = buildModel(childTable, childCols == null ? List.of() : childCols, List.of());
			m.put("childModel", childModel);
			// 子表编辑列：剔除外键列（级联时自动回填），控件按 html_type 映射（row 绑定，子行内嵌编辑）
			List<Map<String, Object>> childForm = new ArrayList<>();
			for (GenColumn c : childCols == null ? List.<GenColumn>of() : childCols) {
				String field = c.getJavaField();
				if (joinField.equals(field) || BASE_FIELDS.contains(field)) {
					continue;
				}
				String label = cleanLabel(nvl(c.getColumnComment(), field));
				String dict = c.getDictType();
				if ("select".equals(c.getHtmlType()) && dict != null && !dict.isBlank()) {
					dictCodes.add(dict);
				}
				childForm.add(Map.of(
					"javaField", field, "comment", label,
					"control", formControl("row." + field, label, c.getHtmlType(), dict, elImports)));
			}
			m.put("childFormColumns", childForm);
		}

		for (GenColumn c : cols) {
			String field = c.getJavaField();
			String label = cleanLabel(nvl(c.getColumnComment(), field));
			// 后端实体列（业务列，剔除 BaseEntity 继承字段）
			if (!BASE_FIELDS.contains(field)) {
				String javaType = normalizeType(c.getJavaType());
				String imp = TYPE_IMPORTS.get(javaType);
				if (imp != null && !imports.contains(imp)) {
					imports.add(imp);
				}
				Map<String, Object> col = new LinkedHashMap<>();
				col.put("javaType", javaType);
				col.put("javaField", field);
				col.put("capField", capitalize(field));
				col.put("comment", label);
				col.put("tsType", tsType(javaType));
				entityColumns.add(col);
			}
			// 前端列表列
			if (isOne(c.getIsList())) {
				listColumns.add(Map.of("prop", field, "label", label));
			}
			// 前端表单列（按控件类型生成对应控件）
			if (isOne(c.getIsEdit())) {
				String dict = c.getDictType();
				if ("select".equals(c.getHtmlType()) && dict != null && !dict.isBlank()) {
					dictCodes.add(dict);
				}
				formColumns.add(Map.of(
					"prop", field, "label", label,
					"control", formControl("currentRow." + field, label, c.getHtmlType(), dict, elImports)));
			}
		}
		imports.sort(String::compareTo);
		// 树表页额外用到的 Element Plus 组件/API
		if (isTree || isMaster) {
			elImports.add("ElTable");
			elImports.add("ElTableColumn");
			elImports.add("ElMessage");
			elImports.add("ElMessageBox");
		}
		if (isMaster) {
			elImports.add("ElPagination");
		}
		m.put("columns", entityColumns);
		// 树表：父级字段由 新增子/新增根 结构化设置，不进编辑表单（且雪花 Long id 不宜入数值控件）
		if (isTree) {
			String parentFieldJava = t.getTreeParentField() != null
				? GenNaming.toCamel(t.getTreeParentField(), false) : "parentId";
			formColumns.removeIf(c -> parentFieldJava.equals(c.get("prop")));
			// 懒加载：控制器按父列取直接子节点（parentColumn 为原始库列名）
			m.put("parentColumn", t.getTreeParentField() != null && !t.getTreeParentField().isBlank()
				? t.getTreeParentField() : "parent_id");
			m.put("parentFieldJava", parentFieldJava);
		}
		m.put("listColumns", listColumns);
		m.put("formColumns", formColumns);
		m.put("imports", imports);
		m.put("elImports", String.join(", ", elImports));
		m.put("dictCodes", new ArrayList<>(dictCodes));
		m.put("dictVars", String.join(", ", dictCodes));
		StringBuilder dictArgs = new StringBuilder();
		for (String d : dictCodes) {
			dictArgs.append(dictArgs.length() > 0 ? ", " : "").append("'").append(d).append("'");
		}
		m.put("dictArgs", dictArgs.toString());
		// 菜单 SQL 用确定性 id（由表配置 id 派生，golden 快照友好、生成产物唯一）
		long tableId = t.getId() == null ? 0L : t.getId();
		m.put("menuId", tableId);
		m.put("addBtnId", tableId + 1);
		m.put("removeBtnId", tableId + 2);
		m.put("parentMenuId", t.getParentMenuId() == null ? 0L : t.getParentMenuId());
		return m;
	}

	/** 按控件类型生成表单控件 XML（并登记所需 Element Plus 组件）；bind 为 v-model 绑定表达式 */
	private String formControl(String bind, String label, String htmlType, String dict, Set<String> elImports) {
		boolean noDict = dict == null || dict.isBlank();
		return switch (htmlType == null ? "input" : htmlType) {
			case "textarea" -> {
				elImports.add("ElInput");
				yield "<ElInput v-model=\"" + bind + "\" type=\"textarea\" :rows=\"3\" placeholder=\"请输入" + label + "\" />";
			}
			case "number" -> {
				elImports.add("ElInputNumber");
				yield "<ElInputNumber v-model=\"" + bind + "\" :controls=\"false\" style=\"width: 100%\" />";
			}
			case "datetime" -> {
				elImports.add("ElDatePicker");
				yield "<ElDatePicker v-model=\"" + bind + "\" type=\"datetime\" value-format=\"YYYY-MM-DD HH:mm:ss\" style=\"width: 100%\" />";
			}
			case "switch" -> {
				elImports.add("ElSwitch");
				yield "<ElSwitch v-model=\"" + bind + "\" :active-value=\"1\" :inactive-value=\"0\" />";
			}
			case "select" -> {
				if (noDict) {
					// 未配置字典时下拉必然为空，降级输入框并明示（防生成「空下拉」糊弄用户）
					elImports.add("ElInput");
					yield "<ElInput v-model=\"" + bind + "\" placeholder=\"请选择" + label + "（未配置字典，暂以输入代替）\" />";
				} else {
					elImports.add("ElSelect");
					elImports.add("ElOption");
					yield "<ElSelect v-model=\"" + bind + "\" placeholder=\"请选择" + label + "\" style=\"width: 100%\">"
						+ "\n            <ElOption v-for=\"d in " + dict + "\" :key=\"d.value\" :label=\"d.label\" :value=\"d.value\" />\n          "
						+ "</ElSelect>";
				}
			}
			case "radio" -> {
				if (noDict) {
					elImports.add("ElInput");
					yield "<ElInput v-model=\"" + bind + "\" placeholder=\"请选择" + label + "（未配置字典，暂以输入代替）\" />";
				} else {
					elImports.add("ElRadioGroup");
					elImports.add("ElRadio");
					yield "<ElRadioGroup v-model=\"" + bind + "\">"
						+ "\n            <ElRadio v-for=\"d in " + dict + "\" :key=\"d.value\" :value=\"d.value\">{{ d.label }}</ElRadio>\n          "
						+ "</ElRadioGroup>";
				}
			}
			case "checkbox" -> {
				if (noDict) {
					elImports.add("ElInput");
					yield "<ElInput v-model=\"" + bind + "\" placeholder=\"请选择" + label + "（未配置字典，暂以输入代替）\" />";
				} else {
					elImports.add("ElCheckboxGroup");
					elImports.add("ElCheckbox");
					yield "<ElCheckboxGroup v-model=\"" + bind + "\">"
						+ "\n            <ElCheckbox v-for=\"d in " + dict + "\" :key=\"d.value\" :value=\"d.value\">{{ d.label }}</ElCheckbox>\n          "
						+ "</ElCheckboxGroup>";
				}
			}
			default -> {
				elImports.add("ElInput");
				yield "<ElInput v-model=\"" + bind + "\" placeholder=\"请输入" + label + "\" />";
			}
		};
	}

	private String render(String template, Map<String, Object> model) {
		return engine.getTemplateByString(readTemplate(template)).renderToString(model);
	}

	private String readTemplate(String name) {
		try {
			return new String(new ClassPathResource("generator/templates/" + name)
				.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
		} catch (IOException e) {
			throw new IllegalStateException("模板读取失败：" + name, e);
		}
	}

	/** 数据库时间类型规约到平台 Java 类型 */
	private String normalizeType(String javaType) {
		if (javaType == null) {
			return "String";
		}
		return switch (javaType) {
			case "Timestamp" -> "LocalDateTime";
			case "Date" -> "LocalDate";
			case "Time" -> "LocalTime";
			default -> javaType;
		};
	}

	/** Java 类型 → TypeScript 类型 */
	private String tsType(String javaType) {
		return switch (javaType) {
			case "Integer", "Long", "BigDecimal", "Double", "Float", "Short" -> "number";
			case "Boolean" -> "boolean";
			default -> "string";
		};
	}

	/** 清洗标签：去换行/制表/反引号/反斜杠/引号/尖括号，并中和 Java 块注释终止符，避免破坏生成的 SFC/Java/SQL */
	private String cleanLabel(String raw) {
		return raw == null ? "" : raw.replaceAll("[\\r\\n\\t`<>\"'\\\\]", " ").replace("*/", " ").trim();
	}

	private boolean isOne(Integer v) {
		return v != null && v == 1;
	}

	private String capitalize(String s) {
		return s == null || s.isEmpty() ? s : Character.toUpperCase(s.charAt(0)) + s.substring(1);
	}

	private String uncapitalize(String s) {
		return s == null || s.isEmpty() ? s : Character.toLowerCase(s.charAt(0)) + s.substring(1);
	}

	private String nvl(String v, String def) {
		return v == null || v.isBlank() ? def : v;
	}
}
