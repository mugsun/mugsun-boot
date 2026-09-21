package com.mugsun.boot.system.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mugsun.boot.common.constant.FlowConstants;
import com.mugsun.core.tool.exception.ServiceException;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 图形流程翻译引擎：把 mugsun 递归节点树（审批/条件分支/并行分支）翻译成 warm-flow 扁平 nodeList+skipList JSON。
 * <p>网关为引擎原生：条件分支=排他网关(nodeType 3，取第一条 skipCondition 为真的边)；并行分支=并行网关
 * (nodeType 4，fork 无条件全激活 + join 原生 AND-join)。会签复用节点 nodeRatio(0或签/100会签/1~99票签)。
 * <p>条件组编译为 warm-flow skipCondition：单条件→ {@code op@@field|value}；多条件→ {@code default@@${SpEL}}；
 * 默认(否则)分支→ {@code default@@${true}} 并置于 skipList 末位（引擎按顺序取第一条为真实现 else 语义）。
 */
@Service
public class FlowGraphService {

	private final ObjectMapper objectMapper;

	public FlowGraphService(ObjectMapper objectMapper) {
		this.objectMapper = objectMapper;
	}

	/** 递归节点：type=approval/condition/parallel；approval 用候选人/比例/表单权限，condition/parallel 用 branches */
	public record TreeNode(String type, String name, List<String> candidates, String role, String nodeRatio,
						   Map<String, String> fieldPerms, List<Branch> branches, TreeNode childNode) {
	}

	/** 分支：condition 用 conditions+logic（空条件=默认否则分支）；parallel 分支无条件；childNode 为分支子流程 */
	public record Branch(String name, List<Rule> conditions, String logic, TreeNode childNode) {
	}

	/** 条件规则：字段 field 与比较值 value，op ∈ eq/ne/gt/ge/lt/le/like/notLike */
	public record Rule(String field, String op, String value) {
	}

	/** 图形设计请求：流程码/名/分类 + 绑定表单 formKey(可空) + 根节点树 */
	public record GraphDesign(String flowCode, String flowName, String category, String formKey, TreeNode root) {
	}

	/** 翻译入口：树 → warm-flow 定义 JSON 字符串 */
	public String buildJson(GraphDesign design) {
		if (design.flowCode() == null || design.flowCode().isBlank()
			|| design.flowName() == null || design.flowName().isBlank()) {
			throw new ServiceException("流程编码与名称不能为空");
		}
		Ctx ctx = new Ctx(design.formKey());
		List<Map<String, Object>> nodes = new ArrayList<>();
		String endCode = "end";
		// 先翻译根子树（终点回指 end），再补 start/end 节点
		String rootEntry = translate(design.root(), endCode, ctx, nodes);

		Map<String, Object> start = baseNode(0, "start", "开始", ctx);
		if (ctx.hasForm()) {
			bindForm(start, ctx.formKey, null);
		}
		start.put("skipList", list(skip("start", rootEntry, "提交", null, ctx)));
		nodes.add(0, start);

		Map<String, Object> end = baseNode(2, endCode, "结束", ctx);
		end.put("skipList", new ArrayList<>());
		nodes.add(end);

		Map<String, Object> root = new LinkedHashMap<>();
		root.put("flowCode", design.flowCode());
		root.put("flowName", design.flowName());
		root.put("modelValue", "CLASSICS");
		root.put("version", "1");
		root.put("formCustom", ctx.hasForm() ? "Y" : "N");
		if (design.category() != null && !design.category().isBlank()) {
			root.put("category", design.category());
		}
		root.put("nodeList", nodes);
		try {
			return objectMapper.writeValueAsString(root);
		} catch (Exception e) {
			throw new ServiceException("流程定义序列化失败：" + e.getMessage());
		}
	}

	/**
	 * 递归翻译一个子树，返回该子树入口节点 code；nextCode = 子树办结后应到达的续接节点 code。
	 */
	private String translate(TreeNode node, String nextCode, Ctx ctx, List<Map<String, Object>> nodes) {
		if (node == null) {
			return nextCode;
		}
		String type = node.type() == null ? "approval" : node.type();
		return switch (type) {
			case "condition" -> exclusiveGateway(node, nextCode, ctx, nodes);
			case "parallel" -> parallelGateway(node, nextCode, ctx, nodes);
			case "approval" -> approval(node, nextCode, ctx, nodes);
			default -> throw new ServiceException("未知节点类型：" + node.type());
		};
	}

	/** 审批节点（nodeType 1）：候选人 permissionFlag + 会签比例 nodeRatio + 可选表单权限 */
	private String approval(TreeNode node, String nextCode, Ctx ctx, List<Map<String, Object>> nodes) {
		String code = ctx.next();
		String cont = translate(node.childNode(), nextCode, ctx, nodes);
		Map<String, Object> n = baseNode(1, code, coalesce(node.name(), "审批"), ctx);
		n.put("permissionFlag", permissionFlag(node));
		n.put("nodeRatio", ratio(node.nodeRatio()));
		if (ctx.hasForm()) {
			bindForm(n, ctx.formKey, node.fieldPerms());
		}
		n.put("skipList", list(skip(code, cont, "通过", null, ctx)));
		nodes.add(n);
		return code;
	}

	/** 条件分支（排他网关 nodeType 3）：每分支一条带 skipCondition 的边，默认分支末位；各分支汇聚到续接节点 */
	private String exclusiveGateway(TreeNode node, String nextCode, Ctx ctx, List<Map<String, Object>> nodes) {
		if (node.branches() == null || node.branches().isEmpty()) {
			throw new ServiceException("条件分支「" + coalesce(node.name(), "") + "」至少需一个分支");
		}
		String code = ctx.next();
		String cont = translate(node.childNode(), nextCode, ctx, nodes);
		List<Branch> ordered = orderDefaultLast(node.branches());
		boolean hasDefault = ordered.stream().anyMatch(b -> b.conditions() == null || b.conditions().isEmpty());
		List<Map<String, Object>> skips = new ArrayList<>();
		for (Branch b : ordered) {
			String entry = translate(b.childNode(), cont, ctx, nodes);
			skips.add(skip(code, entry, coalesce(b.name(), "分支"), compileCondition(b), ctx));
		}
		// 无显式默认分支 → 补隐式兜底边直达续接节点，防所有条件不满足时排他网关无路可走卡死
		if (!hasDefault) {
			skips.add(skip(code, cont, "默认", "default@@${true}", ctx));
		}
		Map<String, Object> gw = baseNode(3, code, coalesce(node.name(), "条件分支"), ctx);
		gw.put("skipList", skips);
		nodes.add(gw);
		return code;
	}

	/** 并行分支（并行网关 nodeType 4）：fork 无条件全激活 + join 原生 AND-join，各分支汇聚到 join */
	private String parallelGateway(TreeNode node, String nextCode, Ctx ctx, List<Map<String, Object>> nodes) {
		if (node.branches() == null || node.branches().size() < 2) {
			throw new ServiceException("并行分支「" + coalesce(node.name(), "") + "」至少需两个分支");
		}
		String fork = ctx.next();
		String join = ctx.next();
		String cont = translate(node.childNode(), nextCode, ctx, nodes);
		List<Map<String, Object>> forkSkips = new ArrayList<>();
		for (Branch b : node.branches()) {
			String entry = translate(b.childNode(), join, ctx, nodes);
			forkSkips.add(skip(fork, entry, coalesce(b.name(), ""), null, ctx));
		}
		Map<String, Object> f = baseNode(4, fork, coalesce(node.name(), "并行开始"), ctx);
		f.put("skipList", forkSkips);
		nodes.add(f);
		Map<String, Object> j = baseNode(4, join, "并行汇聚", ctx);
		j.put("skipList", list(skip(join, cont, "", null, ctx)));
		nodes.add(j);
		return fork;
	}

	// ==================== 条件编译 ====================

	/** 编译分支条件组为 warm-flow skipCondition；空条件=默认否则分支→恒真 */
	private String compileCondition(Branch b) {
		List<Rule> rules = b.conditions();
		if (rules == null || rules.isEmpty()) {
			return "default@@${true}";
		}
		if (rules.size() == 1) {
			Rule r = rules.get(0);
			validateRule(r);
			// 单条件走 core 策略（无 SpEL），数值/字符串由引擎自动判定
			return coreOp(r.op()) + FlowConstants.SEPARATOR + r.field() + "|" + r.value();
		}
		boolean or = "OR".equalsIgnoreCase(b.logic());
		String glue = or ? " || " : " && ";
		StringBuilder sb = new StringBuilder("default@@${");
		for (int i = 0; i < rules.size(); i++) {
			Rule r = rules.get(i);
			validateRule(r);
			if (i > 0) {
				sb.append(glue);
			}
			sb.append(spelExpr(r));
		}
		sb.append("}");
		return sb.toString();
	}

	private void validateRule(Rule r) {
		if (r == null || r.field() == null || r.field().isBlank() || r.op() == null || r.op().isBlank()) {
			throw new ServiceException("条件规则缺少字段或运算符");
		}
		if (!OPS.contains(r.op())) {
			throw new ServiceException("不支持的条件运算符：" + r.op());
		}
		// 变量名限定为合法标识符：杜绝 SpEL 注入（如 T(Runtime)…）与破坏 @@/| 分隔的字符
		if (!FIELD_PATTERN.matcher(r.field().trim()).matches()) {
			throw new ServiceException("条件变量名非法（仅允许字母/数字/下划线，且非数字开头）：" + r.field());
		}
		String v = r.value() == null ? "" : r.value();
		// 比较值杜绝会破坏 warm-flow 条件解析（@@ / |）或换行的字符
		if (v.contains("|") || v.contains(FlowConstants.SEPARATOR) || v.contains("\n") || v.contains("\r")) {
			throw new ServiceException("条件值含非法字符（不能包含 | 或 @@ 或换行）");
		}
	}

	/** core skipCondition 运算符前缀（与 ConditionStrategy 类型对齐） */
	private String coreOp(String op) {
		return op;
	}

	/** 多条件时编译为 SpEL 片段：field &lt;op&gt; value（字符串值加引号、like 用 contains） */
	private String spelExpr(Rule r) {
		String field = r.field();
		String v = spelValue(r.value());
		return switch (r.op()) {
			case "eq" -> field + " == " + v;
			case "ne" -> field + " != " + v;
			case "gt" -> field + " > " + v;
			case "ge" -> field + " >= " + v;
			case "lt" -> field + " < " + v;
			case "le" -> field + " <= " + v;
			case "like" -> field + " != null && " + field + ".contains(" + v + ")";
			case "notLike" -> "(" + field + " == null || !" + field + ".contains(" + v + "))";
			default -> throw new ServiceException("不支持的条件运算符：" + r.op());
		};
	}

	/** SpEL 值字面量：数值原样，其余单引号包裹（SpEL 内单引号转义为两个单引号） */
	private String spelValue(String value) {
		String v = value == null ? "" : value;
		if (v.matches("-?\\d+(\\.\\d+)?")) {
			return v;
		}
		return "'" + v.replace("'", "''") + "'";
	}

	/** 默认(否则)分支排到末位：无条件的分支即默认，保证排他网关顺序求值时 else 语义正确 */
	private List<Branch> orderDefaultLast(List<Branch> branches) {
		List<Branch> normal = new ArrayList<>();
		List<Branch> defaults = new ArrayList<>();
		for (Branch b : branches) {
			if (b.conditions() == null || b.conditions().isEmpty()) {
				defaults.add(b);
			} else {
				normal.add(b);
			}
		}
		normal.addAll(defaults);
		return normal;
	}

	// ==================== 节点/边构造 ====================

	private Map<String, Object> baseNode(int type, String code, String name, Ctx ctx) {
		Map<String, Object> node = new LinkedHashMap<>();
		node.put("nodeType", type);
		node.put("nodeCode", code);
		node.put("nodeName", name);
		int x = ctx.x();
		node.put("coordinate", x + ",200|" + x + ",200");
		return node;
	}

	private Map<String, Object> skip(String from, String to, String name, String condition, Ctx ctx) {
		Map<String, Object> e = new LinkedHashMap<>();
		e.put("nowNodeCode", from);
		e.put("nextNodeCode", to);
		e.put("skipName", name);
		e.put("skipType", "PASS");
		if (condition != null && !condition.isBlank()) {
			e.put("skipCondition", condition);
		}
		int x = ctx.edgeX();
		e.put("coordinate", x + ",200;" + (x + 60) + ",200");
		return e;
	}

	/** 绑定业务表单到节点：复用 G82（formCustom='Y' + formPath + 审批节点附字段权限 ext） */
	private void bindForm(Map<String, Object> node, String formKey, Map<String, String> fieldPerms) {
		node.put("formCustom", "Y");
		node.put("formPath", formKey);
		if (fieldPerms != null && !fieldPerms.isEmpty()) {
			try {
				node.put("ext", objectMapper.writeValueAsString(fieldPerms));
			} catch (Exception e) {
				throw new ServiceException("字段权限序列化失败");
			}
		}
	}

	/** 候选人编码 permissionFlag（多类型 storageId 以 @@ 分隔，兼容旧单角色 role） */
	private String permissionFlag(TreeNode node) {
		if (node.candidates() != null && !node.candidates().isEmpty()) {
			String flag = node.candidates().stream().filter(c -> c != null && !c.isBlank())
				.reduce((a, b) -> a + FlowConstants.SEPARATOR + b).orElse("");
			if (!flag.isBlank()) {
				return flag;
			}
		}
		if (node.role() != null && !node.role().isBlank()) {
			return FlowConstants.CANDIDATE_ROLE + node.role();
		}
		throw new ServiceException("审批节点「" + coalesce(node.name(), "") + "」未配置候选人");
	}

	/** 会签比例：0 或签 / 100 会签 / 1~99 票签；限 0~100 整数，防非法值透传引擎 */
	private String ratio(String nodeRatio) {
		if (nodeRatio == null || nodeRatio.isBlank()) {
			return "0";
		}
		String r = nodeRatio.trim();
		int v;
		try {
			v = Integer.parseInt(r);
		} catch (NumberFormatException e) {
			throw new ServiceException("会签比例非法（须 0~100 整数）：" + nodeRatio);
		}
		if (v < 0 || v > 100) {
			throw new ServiceException("会签比例须为 0~100：" + nodeRatio);
		}
		return r;
	}

	private String coalesce(String v, String fallback) {
		return v == null || v.isBlank() ? fallback : v;
	}

	@SafeVarargs
	private List<Map<String, Object>> list(Map<String, Object>... items) {
		return new ArrayList<>(List.of(items));
	}

	private static final List<String> OPS = List.of("eq", "ne", "gt", "ge", "lt", "le", "like", "notLike");

	/** 条件变量名合法标识符（防 SpEL 注入 + 破坏分隔符） */
	private static final java.util.regex.Pattern FIELD_PATTERN =
		java.util.regex.Pattern.compile("^[A-Za-z_][A-Za-z0-9_]*$");

	/** 翻译上下文：节点编码计数、坐标游标、流程级绑定表单 */
	private static final class Ctx {
		private int seq = 0;
		private int coord = 100;
		private final String formKey;

		Ctx(String formKey) {
			this.formKey = formKey;
		}

		String next() {
			return "nd" + (++seq);
		}

		int x() {
			int v = coord;
			coord += 160;
			return v;
		}

		int edgeX() {
			return coord - 80;
		}

		boolean hasForm() {
			return formKey != null && !formKey.isBlank();
		}
	}
}
