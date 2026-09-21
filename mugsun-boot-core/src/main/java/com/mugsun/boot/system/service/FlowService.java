package com.mugsun.boot.system.service;

import cn.dev33.satoken.stp.StpUtil;
import cn.hutool.core.util.IdUtil;
import com.mugsun.boot.common.constant.FlowConstants;
import com.mugsun.boot.gen.DbDialects;
import com.mugsun.boot.gen.RuntimeSql;
import com.mugsun.core.tool.exception.ServiceException;
import com.mybatisflex.core.row.Db;
import com.mybatisflex.core.row.Row;
import org.dromara.warm.flow.core.FlowEngine;
import org.dromara.warm.flow.core.dto.FlowParams;
import org.dromara.warm.flow.core.entity.Instance;
import org.dromara.warm.flow.core.entity.Task;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 工作流办理服务：审批动作**一律走 warm-flow 引擎 API**（非裸 SQL），承载权限/状态守卫。
 * <p>动作集：通过 skip(PASS) / 退回上一步 rejectLast / 退回指定节点 skip(REJECT)+nodeCode /
 * 撤回 revoke(发起人) / 作废 termination / 转办·委派·加签·减签 单入口 taskOperation / 抄送（应用层，flow_user type='C'）。
 * <p>驳回语义修正：退回走 rejectLast（实例存活、回退上一步），**不再误用 termination（终止作废）**。
 */
@Service
public class FlowService {

	/** 抄送用户类型（warm-flow 引擎不识别，仅应用层读写；引擎办理人为 1/2/3） */
	private static final String USER_TYPE_COPY = "C";

	private final HandlerSelectService selectService;
	private final com.fasterxml.jackson.databind.ObjectMapper objectMapper;

	/**
	 * 金仓：裸名 {@code sys_*} 会命中 SYS_CATALOG，裸 SQL 须 schema 限定业务表。
	 */
	@Value("${mugsun.db.default-schema:}")
	private String defaultDbSchema;

	public FlowService(HandlerSelectService selectService, com.fasterxml.jackson.databind.ObjectMapper objectMapper) {
		this.selectService = selectService;
		this.objectMapper = objectMapper;
	}

	/** 业务表名（可带 default-schema；金仓裸 sys_* 会进 SYS_CATALOG） */
	private String bizTable(String table) {
		return StringUtils.hasText(defaultDbSchema) ? defaultDbSchema + "." + table : table;
	}

	/** 业务 sys_user 表名（可带 schema） */
	private String sysUserTable() {
		return bizTable("sys_user");
	}

	// ==================== 列表读取 ====================

	/** 流程定义列表 */
	public List<Row> definitions() {
		return Db.selectListBySql(
			"select id as \"id\", flow_code as \"flowCode\", flow_name as \"flowName\", version as \"version\", "
				+ "category as \"category\", is_publish as \"isPublish\", activity_status as \"activityStatus\", "
				+ "create_time as \"createTime\" "
				+ "from flow_definition where coalesce(del_flag, '0') <> '1' order by id desc");
	}

	/** 我的待办：按当前用户的角色码/用户标识匹配任务办理人（实例按发起人租户隔离，角色码跨租户同名不可穿透） */
	public List<Row> myTodo() {
		List<String> flags = userFlags();
		String in = flags.stream().map(f -> "?").collect(Collectors.joining(","));
		String tenantSql = tenantPredicate();
		List<Object> args = new ArrayList<>(flags);
		if (tenantSql != null) {
			args.add(com.mugsun.boot.tenant.TenantContext.current());
		}
		return Db.selectListBySql(
			"select t.id as \"taskId\", t.instance_id as \"instanceId\", t.node_code as \"nodeCode\", "
				+ "t.node_name as \"nodeName\", i.business_id as \"businessId\", i.flow_status as \"flowStatus\", "
				+ "d.flow_name as \"flowName\", t.create_time as \"createTime\" "
				+ "from flow_task t "
				+ "join flow_user u on u.associated = t.id and u.type in ('1','2','3') and coalesce(u.del_flag, '0') <> '1' "
				+ "join flow_instance i on i.id = t.instance_id "
				+ "join flow_definition d on d.id = t.definition_id "
				+ "where coalesce(t.del_flag, '0') <> '1' and u.processed_by in (" + in + ") "
				+ (tenantSql == null ? "" : "and " + tenantSql + " ")
				+ "order by t.id desc",
			args.toArray());
	}

	/** 我的抄送：应用层 flow_user type='C'（associated = 实例 id）；实例按发起人租户隔离 */
	public List<Row> myCopy() {
		String tenantSql = tenantPredicate();
		List<Object> args = new ArrayList<>();
		args.add(StpUtil.getLoginIdAsString());
		if (tenantSql != null) {
			args.add(com.mugsun.boot.tenant.TenantContext.current());
		}
		return Db.selectListBySql(
			"select i.id as \"instanceId\", i.business_id as \"businessId\", i.flow_status as \"flowStatus\", "
				+ "d.flow_name as \"flowName\", u.create_time as \"createTime\" "
				+ "from flow_user u "
				+ "join flow_instance i on i.id = u.associated "
				+ "join flow_definition d on d.id = i.definition_id "
				+ "where u.type = '" + USER_TYPE_COPY + "' and coalesce(u.del_flag,'0') <> '1' and u.processed_by = ? "
				+ (tenantSql == null ? "" : "and " + tenantSql + " ")
				+ "order by u.id desc", args.toArray());
	}

	/** 实例历史流转（进度时间线） */
	public List<Row> history(Long instanceId) {
		assertInstanceAccess(instanceId);
		List<Row> rows = Db.selectListBySql(
			"select node_code as \"nodeCode\", node_name as \"nodeName\", approver as \"approver\", "
				+ "skip_type as \"skipType\", flow_status as \"flowStatus\", message as \"message\", "
				+ "create_time as \"createTime\" "
				+ "from flow_his_task where instance_id = ? and coalesce(del_flag, '0') <> '1' order by id asc", instanceId);
		for (Row row : rows) {
			String approver = row.getString("approver");
			if (StringUtils.hasText(approver)) {
				List<Map<String, Object>> names = selectService.usernames(List.of(approver));
				if (!names.isEmpty()) {
					row.set("approver", names.get(0).get("name"));
				}
			}
		}
		return rows;
	}

	/** 实例可退回的历史审批节点（供“退回指定节点”选择；排除开始/结束/网关） */
	public List<Row> backNodes(Long instanceId) {
		assertInstanceAccess(instanceId);
		return Db.selectListBySql(
			"select distinct node_code as \"nodeCode\", node_name as \"nodeName\" "
				+ "from flow_his_task where instance_id = ? and node_type = 1 and coalesce(del_flag,'0') <> '1' "
				+ "order by length(node_code), node_code", instanceId);
	}

	/** 查询实例（含流程状态，含参与人访问校验） */
	public Instance instance(Long instanceId) {
		assertInstanceAccess(instanceId);
		return FlowEngine.insService().getById(instanceId);
	}

	// ==================== 审批中心 ====================

	/** 我发起：当前用户发起的流程实例 */
	public List<Row> myStarted() {
		return Db.selectListBySql(
			"select i.id as \"instanceId\", i.business_id as \"businessId\", i.flow_status as \"flowStatus\", "
				+ "i.node_name as \"nodeName\", d.flow_name as \"flowName\", i.create_time as \"createTime\" "
				+ "from flow_instance i join flow_definition d on d.id = i.definition_id "
				+ "where i.create_by = ? order by i.id desc", StpUtil.getLoginIdAsString());
	}

	/** 已办：当前用户办理过的流程（按实例去重取最近一次办理；实例按发起人租户隔离） */
	public List<Row> myDone() {
		String tenantSql = tenantPredicate();
		List<Object> args = new ArrayList<>();
		args.add(StpUtil.getLoginIdAsString());
		if (tenantSql != null) {
			args.add(com.mugsun.boot.tenant.TenantContext.current());
		}
		return Db.selectListBySql(
			"select h.instance_id as \"instanceId\", i.business_id as \"businessId\", "
				+ "(array_agg(h.node_name order by h.create_time desc))[1] as \"nodeName\", "
				+ "(array_agg(h.skip_type order by h.create_time desc))[1] as \"skipType\", "
				+ "i.flow_status as \"flowStatus\", d.flow_name as \"flowName\", "
				+ "max(h.create_time) as \"createTime\" "
				+ "from flow_his_task h join flow_instance i on i.id = h.instance_id "
				+ "join flow_definition d on d.id = i.definition_id "
				+ "where h.approver = ? and h.node_type = 1 and coalesce(h.del_flag,'0') <> '1' "
				+ (tenantSql == null ? "" : "and " + tenantSql + " ")
				+ "group by h.instance_id, i.business_id, i.flow_status, d.flow_name "
				+ "order by max(h.create_time) desc", args.toArray());
	}

	/** 流程图进度：实例定义的各节点 + 状态（已过 passed / 当前 current / 驳回 rejected / 待处理 pending） */
	public List<Map<String, Object>> progress(Long instanceId) {
		Instance ins = FlowEngine.insService().getById(instanceId);
		if (ins == null) {
			throw new ServiceException("流程实例不存在");
		}
		assertInstanceAccess(instanceId);
		Long defId = ins.getDefinitionId();
		List<Row> nodes = Db.selectListBySql(
			"select node_code as \"nodeCode\", node_name as \"nodeName\", node_type as \"nodeType\" "
				+ "from flow_node where definition_id = ? and coalesce(del_flag,'0') <> '1' "
				+ "order by node_type, length(node_code), node_code",
			defId);
		Set<String> current = codeSet("select node_code as \"v\" from flow_task "
			+ "where instance_id = ? and coalesce(del_flag,'0') <> '1'", instanceId);
		Set<String> passed = codeSet("select distinct node_code as \"v\" from flow_his_task "
			+ "where instance_id = ? and skip_type = 'PASS' and coalesce(del_flag,'0') <> '1'", instanceId);
		Set<String> rejected = codeSet("select distinct node_code as \"v\" from flow_his_task "
			+ "where instance_id = ? and skip_type = 'REJECT' and coalesce(del_flag,'0') <> '1'", instanceId);
		List<Map<String, Object>> result = new ArrayList<>();
		for (Row n : nodes) {
			String code = n.getString("nodeCode");
			String status;
			if (current.contains(code)) {
				// 当前待办节点（含被退回到的节点）
				status = "current";
			} else if (passed.contains(code)) {
				// 已通过优先于历史驳回：退回后又通过的节点呈现为已过
				status = "passed";
			} else if (rejected.contains(code)) {
				status = "rejected";
			} else {
				status = "pending";
			}
			Map<String, Object> node = new LinkedHashMap<>();
			node.put("nodeCode", code);
			node.put("nodeName", n.getString("nodeName"));
			node.put("nodeType", n.getString("nodeType"));
			node.put("status", status);
			result.add(node);
		}
		return result;
	}

	/** 下一节点审批人预测：当前任务通过后到达的下一节点及其解析出的候选审批人 */
	public List<Map<String, Object>> nextApprovers(Long taskId) {
		Task task = requireTask(taskId);
		assertInstanceAccess(task.getInstanceId());
		Instance ins = FlowEngine.insService().getById(task.getInstanceId());
		String initiator = ins == null ? null : ins.getCreateBy();
		List<Row> nexts = Db.selectListBySql(
			"select next_node_code as \"code\" from flow_skip "
				+ "where definition_id = ? and now_node_code = ? and (skip_type = 'PASS' or skip_type is null) "
				+ "and coalesce(del_flag,'0') <> '1'", task.getDefinitionId(), task.getNodeCode());
		List<Map<String, Object>> result = new ArrayList<>();
		for (Row nx : nexts) {
			String nextCode = nx.getString("code");
			Row node = firstRow(
				"select node_type as \"nt\", node_name as \"nn\", permission_flag as \"pf\" "
					+ "from flow_node where definition_id = ? and node_code = ? and coalesce(del_flag,'0') <> '1'",
				task.getDefinitionId(), nextCode);
			if (node == null) {
				continue;
			}
			Map<String, Object> item = new LinkedHashMap<>();
			item.put("nodeCode", nextCode);
			item.put("nodeName", node.getString("nn"));
			if ("2".equals(node.getString("nt"))) {
				item.put("end", true);
				item.put("approvers", List.of());
			} else {
				String pf = node.getString("pf");
				List<String> flags = pf == null ? List.of()
					: List.of(pf.split(java.util.regex.Pattern.quote(FlowConstants.SEPARATOR)));
				item.put("approvers", selectService.usernames(selectService.resolveHandlers(flags, initiator)));
			}
			result.add(item);
		}
		return result;
	}

	/** 通用审批弹窗按钮：当前用户对该任务可用的动作（buttonList 驱动前端按钮显隐） */
	public List<String> taskButtons(Long taskId) {
		Task task = FlowEngine.taskService().getById(taskId);
		if (task == null) {
			return List.of();
		}
		// 跨租户任务不发放动作（实例按发起人租户隔离）
		Instance ins = FlowEngine.insService().getById(task.getInstanceId());
		if (ins == null) {
			return List.of();
		}
		try {
			assertInstanceTenant(ins);
		} catch (ServiceException e) {
			return List.of();
		}
		List<String> flags = userFlags();
		List<String> handlers = Db.selectListBySql(
			"select u.processed_by as \"v\" from flow_user u "
				+ "where u.associated = ? and u.type in ('1','2','3') and coalesce(u.del_flag,'0') <> '1'", taskId)
			.stream().map(r -> r.getString("v")).collect(Collectors.toList());
		boolean assignee = handlers.stream().anyMatch(flags::contains);
		if (!assignee) {
			return List.of();
		}
		return List.of("pass", "reject", "rejectNode", "transfer", "depute",
			"addSignature", "reductionSignature", "copy", "terminate");
	}

	private Set<String> codeSet(String sql, Object arg) {
		return Db.selectListBySql(sql, arg).stream()
			.map(r -> r.getString("v")).collect(Collectors.toSet());
	}

	private Row firstRow(String sql, Object... args) {
		List<Row> rows = Db.selectListBySql(sql, args);
		return rows.isEmpty() ? null : rows.get(0);
	}

	// ==================== 表单绑流程 ====================

	/** 发起表单：流程码最新已发布定义的发起节点绑定表单（全字段可写，无预填数据） */
	public Map<String, Object> startForm(String flowCode) {
		Row def = firstRow("select id as \"id\" from flow_definition "
			+ "where flow_code = ? and is_publish = 1 and coalesce(del_flag,'0') <> '1' order by version desc, id desc",
			flowCode);
		if (def == null) {
			return Map.of("hasForm", false);
		}
		Row start = firstRow("select form_path as \"fp\" from flow_node "
			+ "where definition_id = ? and node_type = 0 and coalesce(del_flag,'0') <> '1'", def.getLong("id"));
		return formPayload(start == null ? null : start.getString("fp"), null, null);
	}

	/** 办理表单：任务节点绑定表单 + 实例已填数据 + 该节点字段级权限 */
	public Map<String, Object> taskForm(Long taskId) {
		Task task = requireTask(taskId);
		assertInstanceAccess(task.getInstanceId());
		Row node = firstRow("select form_path as \"fp\", ext as \"ext\" from flow_node "
			+ "where definition_id = ? and node_code = ? and coalesce(del_flag,'0') <> '1'",
			task.getDefinitionId(), task.getNodeCode());
		if (node == null) {
			return Map.of("hasForm", false);
		}
		Instance ins = FlowEngine.insService().getById(task.getInstanceId());
		return formPayload(node.getString("fp"), ins == null ? null : ins.getVariableMap(),
			parseFieldPerms(node.getString("ext")));
	}

	/** 查看表单：实例业务数据只读回显（发起节点表单 + 全字段 READ） */
	public Map<String, Object> instanceForm(Long instanceId) {
		Instance ins = FlowEngine.insService().getById(instanceId);
		if (ins == null) {
			throw new ServiceException("流程实例不存在");
		}
		assertInstanceAccess(instanceId);
		Row start = firstRow("select form_path as \"fp\" from flow_node "
			+ "where definition_id = ? and node_type = 0 and coalesce(del_flag,'0') <> '1'", ins.getDefinitionId());
		Map<String, Object> payload = formPayload(start == null ? null : start.getString("fp"),
			ins.getVariableMap(), null);
		payload.put("readonly", true);
		return payload;
	}

	/** 组装表单载荷：formKey → sys_form schema/option + 数据 + 字段权限 */
	private Map<String, Object> formPayload(String formKey, Map<String, Object> data, Map<String, String> fieldPerms) {
		Map<String, Object> r = new LinkedHashMap<>();
		if (formKey == null || formKey.isBlank()) {
			r.put("hasForm", false);
			return r;
		}
		Row form = firstRow("select name as \"n\", form_schema as \"s\", form_option as \"o\" "
			+ "from " + bizTable("sys_form") + " where form_key = ? and is_deleted = 0 and status = 1", formKey);
		if (form == null || form.getString("s") == null) {
			r.put("hasForm", false);
			return r;
		}
		r.put("hasForm", true);
		r.put("formKey", formKey);
		r.put("formName", form.getString("n"));
		r.put("schema", form.getString("s"));
		r.put("option", form.getString("o"));
		r.put("data", data == null ? Map.of() : data);
		r.put("fieldPerms", fieldPerms == null ? Map.of() : fieldPerms);
		return r;
	}

	private Map<String, String> parseFieldPerms(String ext) {
		if (ext == null || ext.isBlank()) {
			return Map.of();
		}
		try {
			return objectMapper.readValue(ext, new com.fasterxml.jackson.core.type.TypeReference<Map<String, String>>() {
			});
		} catch (Exception e) {
			return Map.of();
		}
	}

	// ==================== 审批动作（引擎 API） ====================

	/** 通过：可携带办理阶段表单数据合并进流程变量（经 skip 内部 mergeVariable 持久化；写门控剔除只读/隐藏字段） */
	public String pass(Long taskId, String message, Map<String, Object> variable) {
		Task task = requireTaskInTenant(taskId);
		FlowParams params = base().skipType("PASS").message(text(message, "同意"));
		Map<String, Object> safe = sanitizeVariable(task, variable);
		if (!safe.isEmpty()) {
			params.variable(safe);
		}
		Instance ins = FlowEngine.taskService().skip(taskId, params);
		return ins.getFlowStatus();
	}

	/** 写门控：按节点字段权限剔除 READ/NONE 字段；未配置权限时禁止覆盖实例已有变量键（防篡改发起数据/条件字段伪造路径） */
	private Map<String, Object> sanitizeVariable(Task task, Map<String, Object> variable) {
		if (variable == null || variable.isEmpty()) {
			return Map.of();
		}
		Row node = firstRow("select ext as \"ext\" from flow_node "
			+ "where definition_id = ? and node_code = ? and coalesce(del_flag,'0') <> '1'",
			task.getDefinitionId(), task.getNodeCode());
		Map<String, String> perms = node == null ? Map.of() : parseFieldPerms(node.getString("ext"));
		Map<String, Object> out = new LinkedHashMap<>(variable);
		if (perms.isEmpty()) {
			// 默认 fail-closed：未声明字段权限的节点只允许新增键，不得覆盖已存在的流程变量
			Instance ins = FlowEngine.insService().getById(task.getInstanceId());
			if (ins != null && ins.getVariableMap() != null) {
				out.keySet().removeAll(ins.getVariableMap().keySet());
			}
			return out;
		}
		perms.forEach((field, perm) -> {
			if (FlowConstants.FIELD_PERM_READ.equals(perm) || FlowConstants.FIELD_PERM_NONE.equals(perm)) {
				out.remove(field);
			}
		});
		return out;
	}

	/** 退回上一步（实例存活、回退，非终止作废） */
	public String rejectLast(Long taskId, String message) {
		requireTaskInTenant(taskId);
		Instance ins = FlowEngine.taskService().rejectLast(taskId,
			base().message(text(message, "退回上一步")));
		return ins.getFlowStatus();
	}

	/** 退回指定历史节点 */
	public String rejectToNode(Long taskId, String nodeCode, String message) {
		requireTaskInTenant(taskId);
		if (nodeCode == null || nodeCode.isBlank()) {
			throw new ServiceException("请选择退回节点");
		}
		Instance ins = FlowEngine.taskService().skip(taskId,
			base().skipType("REJECT").nodeCode(nodeCode).message(text(message, "退回")));
		return ins.getFlowStatus();
	}

	/** 撤回（仅发起人，引擎校验 handler == 实例 createBy） */
	public String revoke(Long instanceId, String message) {
		Instance ins = FlowEngine.insService().getById(instanceId);
		if (ins == null) {
			throw new ServiceException("流程实例不存在");
		}
		assertInstanceTenant(ins);
		Instance r = FlowEngine.taskService().revoke(instanceId,
			FlowParams.build().handler(uid()).message(text(message, "撤回")));
		return r.getFlowStatus();
	}

	/** 作废（终止整个实例，语义独立于退回） */
	public String terminate(Long taskId, String message) {
		requireTaskInTenant(taskId);
		Instance ins = FlowEngine.taskService().termination(taskId,
			base().message(text(message, "作废")));
		return ins.getFlowStatus();
	}

	/** 转办/委派/加签/减签 单入口：目标办理人 handlers（减签为要移除的人）；目标须为本租户用户 */
	public void taskOperation(Long taskId, String op, List<String> handlers, String message) {
		requireTaskInTenant(taskId);
		if (handlers == null || handlers.isEmpty()) {
			throw new ServiceException("请选择目标办理人");
		}
		assertHandlersInTenant(handlers);
		FlowParams p = base().message(text(message, opName(op)));
		switch (op) {
			case "transfer" -> FlowEngine.taskService().transfer(taskId, p.addHandlers(handlers));
			case "depute" -> FlowEngine.taskService().depute(taskId, p.addHandlers(handlers));
			case "addSignature" -> FlowEngine.taskService().addSignature(taskId, p.addHandlers(handlers));
			case "reductionSignature" -> FlowEngine.taskService().reductionSignature(taskId, p.reductionHandlers(handlers));
			default -> throw new ServiceException("不支持的操作：" + op);
		}
	}

	/** 抄送（应用层）：仅当前任务办理人可发起（与 taskButtons 同口径，防 IDOR 自我授权读实例）；
	 *  被抄送人须为本租户用户。 */
	public void copyTo(Long taskId, List<String> userIds) {
		Task task = requireTaskInTenant(taskId);
		if (userIds == null || userIds.isEmpty()) {
			return;
		}
		List<String> flags = userFlags();
		List<String> handlers = Db.selectListBySql(
			"select u.processed_by as \"v\" from flow_user u "
				+ "where u.associated = ? and u.type in ('1','2','3') and coalesce(u.del_flag,'0') <> '1'", taskId)
			.stream().map(r -> r.getString("v")).collect(Collectors.toList());
		if (handlers.stream().noneMatch(flags::contains) && !com.mugsun.boot.tenant.TenantContext.isPlatformSuperAdmin()) {
			throw new ServiceException("仅该任务办理人可抄送");
		}
		assertHandlersInTenant(userIds);
		Long instanceId = task.getInstanceId();
		for (String u : userIds) {
			Db.updateBySql(RuntimeSql.insertFlowUser(DbDialects.current()),
				IdUtil.getSnowflakeNextId(), USER_TYPE_COPY, u, instanceId);
		}
	}

	/** 目标办理人/被抄送人归属校验：数字 id 须为本租户用户（角色码等非 id 标识交引擎匹配，租户边界由实例归属保证） */
	private void assertHandlersInTenant(List<String> handlers) {
		String tenant = com.mugsun.boot.tenant.TenantContext.current();
		if (tenant == null) {
			return;
		}
		for (String h : handlers) {
			if (h == null || !h.matches("^\\d+$")) {
				continue;
			}
			boolean inTenant = !Db.selectListBySql(
				"select 1 from " + sysUserTable() + " where " + DbDialects.current().castVarchar("id")
					+ " = ? and tenant_id = ? and is_deleted = 0"
					+ DbDialects.current().limitOne(),
				h, tenant).isEmpty();
			if (!inTenant) {
				throw new ServiceException("目标办理人不属于本租户");
			}
		}
	}

	// ==================== 守卫 / 工具 ====================

	/** 校验任务存在且待办（已办任务已流转至历史，flow_task 查无即非法流转） */
	private Task requireTask(Long taskId) {
		Task t = FlowEngine.taskService().getById(taskId);
		if (t == null) {
			throw new ServiceException("任务不存在或已处理");
		}
		return t;
	}

	/**
	 * 参与人访问校验（防越权读 IDOR）：先按实例发起人租户隔离，再判 发起人/当前办理人/历史办理人/被抄送人/平台超管。
	 * my-* 列表已按当前用户 scope，故仅按 instanceId/taskId 拉数据的读端点需此守卫。
	 */
	private void assertInstanceAccess(Long instanceId) {
		if (instanceId == null) {
			throw new ServiceException("流程实例不存在");
		}
		Instance ins = FlowEngine.insService().getById(instanceId);
		if (ins == null) {
			throw new ServiceException("流程实例不存在");
		}
		// 租户隔离：实例归属发起人所属租户，跨租户一律按不存在（角色码跨租户同名不可作为穿透凭据）
		assertInstanceTenant(ins);
		String me = uid();
		if (me.equals(ins.getCreateBy())) {
			return;
		}
		// 管理员旁路仅限平台超管（各租户内置管理员角色码同为 admin，不能跨租户兜底）
		if (com.mugsun.boot.tenant.TenantContext.isPlatformSuperAdmin()) {
			return;
		}
		List<String> flags = userFlags();
		// 当前办理人（flow_user 关联本实例任务）或被抄送人（type='C' 关联实例）
		String in = flags.stream().map(f -> "?").collect(Collectors.joining(","));
		List<Object> args = new ArrayList<>(flags);
		args.add(instanceId);
		args.add(instanceId);
		boolean participant = !Db.selectListBySql(
			"select 1 from flow_user u where coalesce(u.del_flag,'0') <> '1' and u.processed_by in (" + in + ") "
				+ "and (u.associated = ? or u.associated in (select id from flow_task where instance_id = ?))"
				+ DbDialects.current().limitOne(),
			args.toArray()).isEmpty();
		// 历史办理人
		boolean handledBefore = !Db.selectListBySql(
			"select 1 from flow_his_task where instance_id = ? and approver = ?"
				+ DbDialects.current().limitOne(), instanceId, me).isEmpty();
		if (!participant && !handledBefore) {
			throw new ServiceException("无权查看该流程");
		}
	}

	/** 实例租户归属：发起人必须是当前租户成员（超管「查看全部」视图放行）；不满足即按不存在处理 */
	private void assertInstanceTenant(Instance ins) {
		String tenant = com.mugsun.boot.tenant.TenantContext.current();
		if (tenant == null) {
			return;
		}
		boolean inTenant = !Db.selectListBySql(
			"select 1 from " + sysUserTable() + " where " + DbDialects.current().castVarchar("id")
				+ " = ? and tenant_id = ? and is_deleted = 0"
				+ DbDialects.current().limitOne(),
			ins.getCreateBy(), tenant).isEmpty();
		if (!inTenant) {
			throw new ServiceException("流程实例不存在");
		}
	}

	/** 任务动作前置：任务存在 + 实例租户归属（审批动作统一先过此闸，再交引擎校验办理人） */
	private Task requireTaskInTenant(Long taskId) {
		Task task = requireTask(taskId);
		Instance ins = FlowEngine.insService().getById(task.getInstanceId());
		if (ins == null) {
			throw new ServiceException("流程实例不存在");
		}
		assertInstanceTenant(ins);
		return task;
	}

	/** 实例租户过滤 SQL 片段（my-* 列表用）：发起人属当前租户；超管「查看全部」返回 null 不加条件 */
	private String tenantPredicate() {
		String tenant = com.mugsun.boot.tenant.TenantContext.current();
		if (tenant == null) {
			return null;
		}
		// 子查询列必须表别名限定：金仓/PG 在多表 JOIN 下裸 id 会报「字段关联不明确」
		return "i.create_by in (select " + DbDialects.current().castVarchar("su.id") + " from " + sysUserTable()
			+ " su where su.tenant_id = ? and su.is_deleted = 0)";
	}

	/** 引擎办理参数基座：当前办理人 + 权限标识集（ignore 默认 false → 引擎校验办理权限） */
	private FlowParams base() {
		return FlowParams.build().handler(uid()).permissionFlag(userFlags());
	}

	private String uid() {
		return StpUtil.getLoginIdAsString();
	}

	/** 当前用户的办理人标识集合：角色码 + 用户 id */
	private List<String> userFlags() {
		List<String> flags = new ArrayList<>();
		Db.selectListBySql(
			"select r.role_code as \"roleCode\" from " + bizTable("sys_user_role") + " ur "
				+ "join " + bizTable("sys_role") + " r on r.id = ur.role_id "
				+ "where ur.user_id = ? and ur.is_deleted = 0", StpUtil.getLoginIdAsLong())
			.forEach(row -> flags.add(String.valueOf(row.getString("roleCode"))));
		flags.add(uid());
		return flags;
	}

	private String text(String message, String fallback) {
		return message == null || message.isBlank() ? fallback : message;
	}

	private String opName(String op) {
		return switch (op) {
			case "transfer" -> "转办";
			case "depute" -> "委派";
			case "addSignature" -> "加签";
			case "reductionSignature" -> "减签";
			default -> op;
		};
	}
}
