package com.mugsun.boot.ai.weave;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mugsun.boot.ai.entity.AiModel;
import com.mugsun.boot.ai.service.AiModelBizService;
import com.mugsun.boot.ai.support.AiLlmClient;
import com.mugsun.boot.gen.GenAiDraftEnhancer;
import com.mugsun.boot.gen.GenNaming;
import com.mugsun.boot.gen.entity.GenColumn;
import com.mugsun.boot.gen.entity.GenTable;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * LLM 增强建表 draft：产出结构化候选或可给规则解析器消费的英文描述；失败返回 empty 回落规则。
 */
@Component
public class GenAiDraftEnhancerImpl implements GenAiDraftEnhancer {

	private static final String SYSTEM = """
		你是低代码建表助手。根据用户自然语言输出 JSON（不要 markdown）：
		{"tableName":"英文蛇形表名","tableComment":"中文表注释","columns":[{"name":"英文列名","comment":"中文","javaType":"String|Integer|Long|BigDecimal|LocalDateTime|LocalDate","htmlType":"input|textarea|number|datetime|switch|select"}]}
		表名/列名须匹配 ^[a-z][a-z0-9_]{0,62}$，不要系统前缀 sys_/gen_/flyway_/flow_/quartz_/act_。
		""";

	private final AiModelBizService modelBizService;
	private final AiLlmClient llmClient;
	private final ObjectMapper objectMapper;

	public GenAiDraftEnhancerImpl(AiModelBizService modelBizService, AiLlmClient llmClient,
								  ObjectMapper objectMapper) {
		this.modelBizService = modelBizService;
		this.llmClient = llmClient;
		this.objectMapper = objectMapper;
	}

	@Override
	public Optional<String> enhanceDescription(String naturalLanguage) {
		try {
			Optional<Map<String, Object>> c = draftCandidate(naturalLanguage);
			if (c.isEmpty()) {
				return Optional.empty();
			}
			@SuppressWarnings("unchecked")
			GenTable table = (GenTable) c.get().get("table");
			@SuppressWarnings("unchecked")
			List<GenColumn> columns = (List<GenColumn>) c.get().get("columns");
			StringBuilder sb = new StringBuilder();
			sb.append(table.getTableName()).append(' ').append(nullToEmpty(table.getTableComment()));
			for (GenColumn col : columns) {
				if ("id".equals(col.getColumnName()) || "create_time".equals(col.getColumnName())
					|| "update_time".equals(col.getColumnName()) || "is_deleted".equals(col.getColumnName())) {
					continue;
				}
				sb.append("；").append(col.getColumnName()).append(' ')
					.append(nullToEmpty(col.getColumnComment())).append(' ')
					.append(typeHint(col.getJavaType()));
			}
			return Optional.of(sb.toString());
		} catch (Exception e) {
			return Optional.empty();
		}
	}

	@Override
	public Optional<Map<String, Object>> draftCandidate(String naturalLanguage) {
		try {
			AiModel model = modelBizService.requireDefaultChat();
			String raw = llmClient.chat(model, List.of(
				Map.of("role", "system", "content", SYSTEM),
				Map.of("role", "user", "content", naturalLanguage)
			), 2048);
			String json = extractJson(raw);
			JsonNode root = objectMapper.readTree(json);
			String tableName = root.path("tableName").asText("").trim().toLowerCase();
			if (!GenNaming.isIdentifier(tableName) || isProtectedTable(tableName)) {
				return Optional.empty();
			}
			GenTable table = new GenTable();
			table.setTableName(tableName);
			table.setTableComment(root.path("tableComment").asText(tableName));
			String stripped = GenNaming.stripPrefix(tableName, "");
			table.setEntityName(GenNaming.toCamel(stripped, true));
			table.setBusinessName(GenNaming.toBusinessName(stripped));
			table.setModuleName("system");
			table.setFunctionName(table.getTableComment());
			table.setFunctionAuthor("mugsun");
			table.setBasePackage("com.mugsun.boot");
			table.setGenType("zip");
			table.setTplCategory("crud");

			List<GenColumn> columns = new ArrayList<>();
			int sort = 1;
			columns.add(audit("id", "主键", "Long", "input", 1, 0, 0, sort++));
			JsonNode arr = root.path("columns");
			if (arr.isArray()) {
				for (JsonNode n : arr) {
					String name = n.path("name").asText("").trim().toLowerCase();
					if (!GenNaming.isIdentifier(name) || "id".equals(name)) {
						continue;
					}
					String javaType = n.path("javaType").asText("String");
					String htmlType = n.path("htmlType").asText("input");
					columns.add(field(name, n.path("comment").asText(name), javaType, htmlType, sort++));
				}
			}
			if (columns.size() <= 1) {
				return Optional.empty();
			}
			columns.add(audit("create_time", "创建时间", "LocalDateTime", "datetime", 0, 1, 0, sort++));
			columns.add(audit("update_time", "更新时间", "LocalDateTime", "datetime", 0, 0, 0, sort++));
			columns.add(audit("is_deleted", "逻辑删除", "Integer", "switch", 0, 0, 0, sort++));

			Map<String, Object> candidate = new LinkedHashMap<>();
			candidate.put("table", table);
			candidate.put("columns", columns);
			return Optional.of(candidate);
		} catch (Exception e) {
			return Optional.empty();
		}
	}

	private static GenColumn field(String name, String comment, String javaType, String htmlType, int sort) {
		GenColumn c = new GenColumn();
		c.setColumnName(name);
		c.setColumnComment(comment);
		c.setJavaType(javaType);
		c.setJavaField(GenNaming.toCamel(name, false));
		c.setHtmlType(htmlType);
		c.setIsPk(0);
		c.setIsIncrement(0);
		c.setIsRequired(0);
		c.setIsInsert(1);
		c.setIsEdit(1);
		c.setIsList(1);
		c.setIsQuery(0);
		c.setQueryType("String".equals(javaType) ? "LIKE" : "EQ");
		c.setSort(sort);
		return c;
	}

	private static GenColumn audit(String name, String comment, String javaType, String htmlType,
								   int pk, int list, int edit, int sort) {
		GenColumn c = field(name, comment, javaType, htmlType, sort);
		c.setIsPk(pk);
		c.setIsRequired(pk);
		c.setIsInsert(0);
		c.setIsEdit(edit);
		c.setIsList(list);
		c.setQueryType("EQ");
		return c;
	}

	private static String extractJson(String raw) {
		if (raw == null) {
			return "{}";
		}
		String s = raw.trim();
		int a = s.indexOf('{');
		int b = s.lastIndexOf('}');
		if (a >= 0 && b > a) {
			return s.substring(a, b + 1);
		}
		return s;
	}

	private static String typeHint(String javaType) {
		if (javaType == null) {
			return "文本";
		}
		return switch (javaType) {
			case "Integer", "Long" -> "整数";
			case "BigDecimal" -> "金额";
			case "LocalDateTime" -> "日期时间";
			case "LocalDate" -> "日期";
			default -> "文本";
		};
	}

	private static String nullToEmpty(String s) {
		return s == null ? "" : s;
	}

	private static boolean isProtectedTable(String tableName) {
		String n = tableName.toLowerCase();
		return n.startsWith("sys_") || n.startsWith("gen_") || n.startsWith("flyway_")
			|| n.startsWith("flow_") || n.startsWith("qrtz_") || n.startsWith("act_")
			|| n.startsWith("ai_");
	}
}
