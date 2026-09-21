package com.mugsun.boot.gen;

import com.mugsun.boot.gen.entity.GenColumn;
import com.mugsun.boot.gen.entity.GenTable;
import com.mugsun.core.tool.exception.ServiceException;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 规则建模（原 AI 辅助建模）：按格式描述 → 候选可编辑元数据（gen_table + gen_column），**仅产出候选、绝不落库/建表**，
 * 由前端展示供人工确认修改，确认后才走建表通道。
 * <p>当前为规则解析器（英文标识符 + 中文标签 + 类型词），**基于规则解析，不支持自由中文描述**；
 * 作为 D9 大模型通道的可替换占位：解析契约稳定、产物结构与 LLM 输出一致，D9 接入后仅替换本解析实现即可。
 */
@Service
public class AiModelService {

	private static final Pattern IDENT = Pattern.compile("[a-z][a-z0-9_]{0,62}");
	/** 段分隔：换行 / 中英逗号 / 顿号 / 分号 / 竖线 */
	private static final Pattern SEG = Pattern.compile("[\\n\\r,，、;；|]+");

	/** 描述 → 候选元数据（table + columns），不持久化 */
	public Map<String, Object> draft(String description) {
		if (description == null || description.isBlank()) {
			throw new ServiceException("请用一句话描述要建的表（含英文表名/字段名 + 中文含义 + 类型）");
		}
		String[] segs = SEG.split(description.trim());
		if (segs.length < 2) {
			throw new ServiceException("至少描述表名与一个字段，例如：product_comment 商品评论；content 内容 文本，score 评分 整数");
		}
		String[] head = parseIdentAndLabel(segs[0]);
		String tableName = head[0];
		if (tableName == null) {
			throw new ServiceException("首段需含英文表名，例如：product_comment 商品评论");
		}
		String tableComment = head[1];

		GenTable table = buildTable(tableName, tableComment);
		List<GenColumn> columns = new ArrayList<>();
		int sort = 1;
		columns.add(auditColumn("id", "主键", "Long", 1, 0, 0, sort++));
		for (int i = 1; i < segs.length; i++) {
			String[] fl = parseIdentAndLabel(segs[i]);
			if (fl[0] == null) {
				continue; // 无英文字段名的段跳过（占位解析器不臆造命名，交 D9 LLM）
			}
			String[] type = inferType(segs[i]);
			columns.add(fieldColumn(fl[0], fl[1], type[0], type[1], sort++));
		}
		if (columns.size() == 1) {
			throw new ServiceException("未解析到有效字段（字段段需含英文字段名）");
		}
		columns.add(auditColumn("create_time", "创建时间", "LocalDateTime", 0, 1, 0, sort++));
		columns.add(auditColumn("update_time", "更新时间", "LocalDateTime", 0, 0, 0, sort++));
		columns.add(auditColumn("is_deleted", "逻辑删除", "Integer", 0, 0, 0, sort++));

		Map<String, Object> candidate = new LinkedHashMap<>();
		candidate.put("table", table);
		candidate.put("columns", columns);
		return candidate;
	}

	private GenTable buildTable(String tableName, String comment) {
		String stripped = GenNaming.stripPrefix(tableName, "");
		GenTable t = new GenTable();
		t.setTableName(tableName);
		t.setTableComment(comment);
		t.setEntityName(GenNaming.toCamel(stripped, true));
		t.setBusinessName(GenNaming.toBusinessName(stripped));
		t.setModuleName("system");
		t.setFunctionName(comment == null || comment.isBlank() ? GenNaming.toCamel(stripped, true) : comment);
		t.setFunctionAuthor("mugsun");
		t.setBasePackage("com.mugsun.boot");
		t.setGenType("zip");
		t.setTplCategory("crud");
		return t;
	}

	private GenColumn fieldColumn(String name, String comment, String javaType, String htmlType, int sort) {
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
		c.setDictType(null);
		c.setSort(sort);
		return c;
	}

	private GenColumn auditColumn(String name, String comment, String javaType, int pk, int list, int edit, int sort) {
		GenColumn c = new GenColumn();
		c.setColumnName(name);
		c.setColumnComment(comment);
		c.setJavaType(javaType);
		c.setJavaField(GenNaming.toCamel(name, false));
		c.setHtmlType("id".equals(name) ? "input" : ("is_deleted".equals(name) ? "switch" : "datetime"));
		c.setIsPk(pk);
		c.setIsIncrement(0);
		c.setIsRequired(pk);
		c.setIsInsert(0);
		c.setIsEdit(edit);
		c.setIsList(list);
		c.setIsQuery(0);
		c.setQueryType("EQ");
		c.setSort(sort);
		return c;
	}

	/** 段首英文标识符 + 中文标签（去标识符/类型词后余下的中文） */
	private String[] parseIdentAndLabel(String seg) {
		String s = seg.trim();
		Matcher m = IDENT.matcher(s);
		String ident = m.find() ? m.group() : null;
		String label = s;
		if (ident != null) {
			label = label.replaceFirst(Pattern.quote(ident), "");
		}
		// 去掉常见类型词，剩下作标签
		label = label.replaceAll("(长文本|富文本|长整数|日期时间|文本|字符串|名称|整数|数量|库存|个数|数字|金额|价格|小数|编号|时间|日期|开关|是否|布尔|状态|类型|下拉|text|textarea|varchar|string|int|integer|long|bigint|decimal|money|datetime|timestamp|date|boolean|switch|select)", "").trim();
		return new String[]{ident, label.isBlank() ? null : label};
	}

	/** 类型词推断（顺序敏感：长文本先于文本、日期时间先于日期、长整数先于整数） */
	private String[] inferType(String seg) {
		String s = seg;
		if (has(s, "长文本", "富文本", "描述", "备注", "内容", "text", "textarea")) {
			return new String[]{"String", "textarea"};
		}
		if (has(s, "金额", "价格", "小数", "decimal", "money")) {
			return new String[]{"BigDecimal", "number"};
		}
		if (has(s, "长整数", "编号", "bigint", "long")) {
			return new String[]{"Long", "number"};
		}
		if (has(s, "整数", "数量", "库存", "个数", "评分", "int", "integer", "数字")) {
			return new String[]{"Integer", "number"};
		}
		if (has(s, "日期时间", "时间", "datetime", "timestamp")) {
			return new String[]{"LocalDateTime", "datetime"};
		}
		if (has(s, "日期", "date")) {
			return new String[]{"LocalDate", "datetime"};
		}
		if (has(s, "开关", "是否", "布尔", "boolean", "switch")) {
			return new String[]{"Integer", "switch"};
		}
		if (has(s, "状态", "类型", "下拉", "select")) {
			return new String[]{"String", "select"};
		}
		return new String[]{"String", "input"};
	}

	private boolean has(String s, String... keys) {
		String low = s.toLowerCase();
		for (String k : keys) {
			if (low.contains(k.toLowerCase())) {
				return true;
			}
		}
		return false;
	}
}
