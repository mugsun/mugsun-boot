package com.mugsun.boot.system.controller;

import cn.dev33.satoken.annotation.SaCheckLogin;
import cn.dev33.satoken.stp.StpUtil;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mugsun.boot.common.repeat.RepeatSubmit;
import com.mugsun.boot.system.service.FlowGraphService;
import com.mugsun.boot.system.service.FlowService;
import com.mugsun.core.tool.api.R;
import com.mugsun.core.tool.exception.ServiceException;
import com.mybatisflex.core.row.Row;
import org.dromara.warm.flow.core.FlowEngine;
import org.dromara.warm.flow.core.dto.FlowParams;
import org.dromara.warm.flow.core.entity.Definition;
import org.dromara.warm.flow.core.entity.Instance;
import org.dromara.warm.flow.core.exception.FlowException;
import org.springframework.core.io.ClassPathResource;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 工作流治理：流程定义部署/发起 + 待办工作台 + 审批动作全集（办理/退回/撤回/转办/委派/加减签/作废/抄送）。
 * 审批动作委派 {@link FlowService} 走 warm-flow 引擎 API；防重办 {@link RepeatSubmit}。
 */
@RestController
@RequestMapping("/system/flow")
@SaCheckLogin
public class FlowController {

	private final ObjectMapper objectMapper;
	private final FlowService flowService;
	private final FlowGraphService flowGraphService;

	public FlowController(ObjectMapper objectMapper, FlowService flowService, FlowGraphService flowGraphService) {
		this.objectMapper = objectMapper;
		this.flowService = flowService;
		this.flowGraphService = flowGraphService;
	}

	/** 流程定义列表 */
	@GetMapping("/definitions")
	public R<List<Row>> definitions() {
		return R.data(flowService.definitions());
	}

	/** 部署：导入请假流程定义 JSON 并发布 */
	@PostMapping("/deploy")
	public R<Long> deploy() throws Exception {
		String json = new String(new ClassPathResource("flow/leave.json").getInputStream()
			.readAllBytes(), StandardCharsets.UTF_8);
		Definition def = FlowEngine.defService().importJson(json);
		FlowEngine.defService().publish(def.getId());
		return R.data(def.getId());
	}

	/**
	 * 图形设计部署：由结构化设计（流程码/名 + 审批节点[名称,审批角色,通过比例]）生成 warm-flow 定义 JSON 并发布。
	 * 服务端组装 nodeList（开始→N个审批→结束）与坐标/连线，保证 JSON 合法。
	 */
	@PostMapping("/design")
	public R<Long> design(@RequestBody FlowDesign req) throws Exception {
		if (req.flowCode() == null || req.flowCode().isBlank() || req.flowName() == null || req.flowName().isBlank()) {
			throw new ServiceException("流程编码与名称不能为空。请填上编码和名称后再发布");
		}
		if (req.nodes() == null || req.nodes().isEmpty()) {
			throw new ServiceException("至少设计一个审批节点。请加上一个审批节点后再发布");
		}
		String json = buildFlowJson(req);
		Definition def = FlowEngine.defService().importJson(json);
		FlowEngine.defService().publish(def.getId());
		return R.data(def.getId());
	}

	/**
	 * 图形流程部署：由递归节点树（审批/条件分支/并行分支/会签）翻译成 warm-flow 定义 JSON 并发布。
	 * 网关为引擎原生（排他 nodeType3 / 并行 nodeType4），条件边编译为 skipCondition。
	 */
	@PostMapping("/design-graph")
	public R<Long> designGraph(@RequestBody FlowGraphService.GraphDesign req) {
		String json = flowGraphService.buildJson(req);
		Definition def = FlowEngine.defService().importJson(json);
		FlowEngine.defService().publish(def.getId());
		return R.data(def.getId());
	}

	// ==================== 流程定义管理（版本/发布/停用/分类，RBAC 管控） ====================

	/** 发布定义 */
	@PostMapping("/definition/publish/{id}")
	public R<Void> publishDefinition(@PathVariable Long id) {
		FlowEngine.defService().publish(id);
		return R.success("已发布");
	}

	/** 取消发布（转未发布） */
	@PostMapping("/definition/unpublish/{id}")
	public R<Void> unpublishDefinition(@PathVariable Long id) {
		FlowEngine.defService().unPublish(id);
		return R.success("已取消发布");
	}

	/** 启用（激活） */
	@PostMapping("/definition/active/{id}")
	public R<Void> activeDefinition(@PathVariable Long id) {
		FlowEngine.defService().active(id);
		return R.success("已启用");
	}

	/** 停用（挂起，不再受理新发起） */
	@PostMapping("/definition/suspend/{id}")
	public R<Void> suspendDefinition(@PathVariable Long id) {
		FlowEngine.defService().unActive(id);
		return R.success("已停用");
	}

	/** 复制为新版本（在已发布版本基础上升版本编辑） */
	@PostMapping("/definition/copy/{id}")
	public R<Void> copyDefinition(@PathVariable Long id) {
		FlowEngine.defService().copyDef(id);
		return R.success("已复制新版本");
	}

	/** 删除定义 */
	@PostMapping("/definition/remove")
	public R<Void> removeDefinition(@RequestBody(required = false) JsonNode body) {
		StpUtil.checkPermission("sys:flow:definition");
		List<Long> ids = parseDefinitionIds(body);
		List<Row> rows = flowService.definitionsByIds(ids);
		if (rows.size() != ids.size()) {
			throw new ServiceException("这个流程不存在。请刷新列表后再试");
		}
		List<String> started = flowService.startedDefinitionNames(ids);
		if (!started.isEmpty()) {
			throw new ServiceException("「" + started.get(0) + "」已经有人发起过。请改为停用，不要删除");
		}
		String name = rows.get(0).getString("flowName");
		try {
			FlowEngine.defService().removeDef(ids);
		} catch (FlowException ex) {
			throw new ServiceException("「" + name + "」已经有人发起过。请改为停用，不要删除");
		}
		if (rows.size() == 1) {
			return R.success("已删除「" + name + "」。列表里不会再出现，也不能再发起");
		}
		return R.success("已删除 " + rows.size() + " 个流程。列表里不会再出现，也不能再发起");
	}

	private List<Long> parseDefinitionIds(JsonNode body) {
		if (body == null || !body.isArray() || body.isEmpty()) {
			throw new ServiceException("请先在列表里选择要删除的流程");
		}
		List<Long> ids = new ArrayList<>();
		for (JsonNode node : body) {
			String text = node == null || node.isNull() ? "" : node.asText("").trim();
			if (!text.matches("\\d{1,19}")) {
				throw new ServiceException("流程编号请填写数字，可以从列表里复制");
			}
			long id = Long.parseLong(text);
			if (!ids.contains(id)) {
				ids.add(id);
			}
		}
		if (ids.isEmpty()) {
			throw new ServiceException("请先在列表里选择要删除的流程");
		}
		return ids;
	}

	/** 发起流程实例（沿用请假流程码 leave，兼容旧入口） */
	@PostMapping("/start/{businessId}")
	public R<Long> start(@PathVariable String businessId) {
		Instance ins = FlowEngine.insService().start(businessId,
			FlowParams.build().flowCode("leave").handler(StpUtil.getLoginIdAsString()));
		return R.data(ins.getId());
	}

	/** 发起请假：必须填写起止时间和理由，并随流程记下，方便审批时查看。 */
	@PostMapping("/leave/start")
	@RepeatSubmit(message = "请勿重复发起。请到审批中心的我发起里确认这张请假单是否已经提交")
	public R<Long> startLeave(@RequestBody LeaveStart leave) {
		if (leave == null || leave.businessId() == null || leave.businessId().isBlank()) {
			throw new ServiceException("请填写业务单号。用字母或数字开头，方便以后在我发起里找到这张请假单");
		}
		String businessId = leave.businessId().trim();
		if (!businessId.matches("^[A-Za-z0-9][A-Za-z0-9_-]{0,63}$")) {
			throw new ServiceException("业务单号请使用字母或数字开头，只能包含字母、数字、下划线或连字符");
		}
		LocalDateTime start = parseLeaveTime(leave.startTime(), "请选择开始时间");
		LocalDateTime end = parseLeaveTime(leave.endTime(), "请选择结束时间");
		if (!end.isAfter(start)) {
			throw new ServiceException("结束时间要晚于开始时间。请重新选择后再提交");
		}
		if (leave.reason() == null || leave.reason().isBlank()) {
			throw new ServiceException("请填写请假理由，说明为什么要请假");
		}
		String reason = leave.reason().trim();
		if (reason.length() > 500) {
			throw new ServiceException("请假理由请控制在 500 字以内");
		}
		flowService.assertLeaveBusinessFree(businessId);
		Map<String, Object> variable = new LinkedHashMap<>();
		variable.put("startTime", leave.startTime().trim());
		variable.put("endTime", leave.endTime().trim());
		variable.put("reason", reason);
		try {
			Instance ins = FlowEngine.insService().start(businessId, FlowParams.build()
				.flowCode("leave")
				.handler(StpUtil.getLoginIdAsString())
				.variable(variable));
			return R.data(ins.getId());
		} catch (ServiceException ex) {
			throw ex;
		} catch (RuntimeException ex) {
			throw new ServiceException("请假没有发起。请确认请假流程已发布，并换一个还没用过的业务单号后再试");
		}
	}

	private static LocalDateTime parseLeaveTime(String raw, String emptyMessage) {
		if (raw == null || raw.isBlank()) {
			throw new ServiceException(emptyMessage + "。时间格式如 2026-10-05 09:00:00");
		}
		String text = raw.trim().replace('T', ' ');
		if (text.length() == 16) {
			text = text + ":00";
		}
		try {
			return LocalDateTime.parse(text, DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
		} catch (DateTimeParseException ex) {
			throw new ServiceException("时间请使用 2026-10-05 09:00:00 这样的格式");
		}
	}

	public record LeaveStart(String businessId, String startTime, String endTime, String reason) {
	}

	/** 发起指定流程码的实例（设计流程发起用）；可选 handlers=发起人自选的首个审批节点办理人、variable=发起业务数据 */
	@PostMapping("/start-by/{flowCode}/{businessId}")
	public R<Long> startBy(@PathVariable String flowCode, @PathVariable String businessId,
						   @RequestBody(required = false) StartParam param) {
		FlowParams flowParams = FlowParams.build().flowCode(flowCode).handler(StpUtil.getLoginIdAsString());
		if (param != null && param.handlers() != null && !param.handlers().isEmpty()) {
			// 发起人自选：覆盖首个审批节点办理人
			flowParams.nextHandler(param.handlers().toArray(new String[0]));
		}
		if (param != null && param.variable() != null && !param.variable().isEmpty()) {
			// 发起携带业务数据 → 落 flow_instance.variable
			flowParams.variable(param.variable());
		}
		Instance ins = FlowEngine.insService().start(businessId, flowParams);
		return R.data(ins.getId());
	}

	// ==================== 表单绑流程 ====================

	/** 发起表单：流程码的发起节点绑定表单（发起页填业务数据，全字段可写） */
	@GetMapping("/form/start")
	public R<Map<String, Object>> startForm(@RequestParam String flowCode) {
		return R.data(flowService.startForm(flowCode));
	}

	/** 办理表单：任务所属节点绑定表单 + 已填数据 + 字段级权限（READ/WRITE/NONE） */
	@GetMapping("/form/task")
	public R<Map<String, Object>> taskForm(@RequestParam Long taskId) {
		return R.data(flowService.taskForm(taskId));
	}

	/** 查看表单：实例业务数据只读回显（我发起/已办详情） */
	@GetMapping("/form/instance")
	public R<Map<String, Object>> instanceForm(@RequestParam Long instanceId) {
		return R.data(flowService.instanceForm(instanceId));
	}

	// ==================== 待办 / 抄送 / 进度 ====================

	/** 我的待办 */
	@GetMapping("/my-todo")
	public R<List<Row>> myTodo() {
		return R.data(flowService.myTodo());
	}

	/** 待签收：多人共同办理、还没被单独签下的任务 */
	@GetMapping("/claim-list")
	public R<List<Row>> claimList() {
		return R.data(flowService.claimList());
	}

	/** 签收后只留给当前用户 */
	@RepeatSubmit(message = "请勿重复签收。如果已经签下，请到待办里办理")
	@PostMapping("/claim/{taskId}")
	public R<Void> claim(@PathVariable Long taskId) {
		flowService.claim(taskId);
		return R.success("已签收，请到待办里办理");
	}

	/** 正在办理的流程 */
	@GetMapping("/running")
	@cn.dev33.satoken.annotation.SaCheckPermission("sys:flow:running")
	public R<List<Row>> running(@RequestParam(required = false) String instanceId,
								@RequestParam(required = false) String flowCode) {
		return R.data(flowService.running(instanceId, flowCode));
	}

	/** 填写原因后终止 */
	@RepeatSubmit(message = "请勿重复终止。请刷新列表确认流程是否已经停下")
	@PostMapping("/running/terminate")
	@cn.dev33.satoken.annotation.SaCheckPermission("sys:flow:running:terminate")
	public R<Void> terminateRunning(@RequestBody Map<String, Object> body) {
		flowService.terminateRunning(longOf(body.get("instanceId")), textOf(body.get("reason")));
		return R.success("流程已终止");
	}

	/** 挂起 */
	@PostMapping("/running/suspend")
	@cn.dev33.satoken.annotation.SaCheckPermission("sys:flow:running:active")
	public R<Void> suspendRunning(@RequestBody Map<String, Object> body) {
		flowService.suspendRunning(longOf(body.get("instanceId")));
		return R.success("流程已挂起");
	}

	/** 恢复 */
	@PostMapping("/running/resume")
	@cn.dev33.satoken.annotation.SaCheckPermission("sys:flow:running:active")
	public R<Void> resumeRunning(@RequestBody Map<String, Object> body) {
		flowService.resumeRunning(longOf(body.get("instanceId")));
		return R.success("流程已恢复");
	}

	/** 我的抄送 */
	@GetMapping("/my-copy")
	public R<List<Row>> myCopy() {
		return R.data(flowService.myCopy());
	}

	/** 实例历史流转（进度时间线） */
	@GetMapping("/history")
	public R<List<Row>> history(@RequestParam Long instanceId) {
		return R.data(flowService.history(instanceId));
	}

	/** 可退回的历史审批节点（退回指定节点的选项） */
	@GetMapping("/back-nodes")
	public R<List<Row>> backNodes(@RequestParam Long instanceId) {
		return R.data(flowService.backNodes(instanceId));
	}

	/** 查询实例（含流程状态，含参与人访问校验） */
	@GetMapping("/instance")
	public R<Instance> instance(@RequestParam Long instanceId) {
		return R.data(flowService.instance(instanceId));
	}

	/** 审批中心·我发起 */
	@GetMapping("/my-started")
	public R<List<Row>> myStarted() {
		return R.data(flowService.myStarted());
	}

	/** 审批中心·已办 */
	@GetMapping("/my-done")
	public R<List<Row>> myDone() {
		return R.data(flowService.myDone());
	}

	/** 流程图进度：各节点状态（已过/当前/驳回/待处理） */
	@GetMapping("/progress")
	public R<List<Map<String, Object>>> progress(@RequestParam Long instanceId) {
		return R.data(flowService.progress(instanceId));
	}

	/** 下一节点审批人预测 */
	@GetMapping("/next-approvers")
	public R<List<Map<String, Object>>> nextApprovers(@RequestParam Long taskId) {
		return R.data(flowService.nextApprovers(taskId));
	}

	/** 通用审批弹窗按钮（buttonList，按当前用户对该任务的可用动作） */
	@GetMapping("/task-buttons")
	public R<List<String>> taskButtons(@RequestParam Long taskId) {
		return R.data(flowService.taskButtons(taskId));
	}

	// ==================== 审批动作（任务办理，鉴权由 warm-flow 办理人校验，豁免 RBAC 码守卫） ====================

	/** 办理（通过）：可携带办理阶段表单数据合并进流程变量 */
	@RepeatSubmit
	@PostMapping("/task/handle/{taskId}")
	public R<String> handle(@PathVariable Long taskId, @RequestBody(required = false) ActionParam param) {
		return R.data(flowService.pass(taskId, msg(param), param == null ? null : param.variable()));
	}

	/** 退回上一步（修正：退回而非终止作废） */
	@RepeatSubmit
	@PostMapping("/task/reject/{taskId}")
	public R<String> reject(@PathVariable Long taskId, @RequestBody(required = false) ActionParam param) {
		return R.data(flowService.rejectLast(taskId, msg(param)));
	}

	/** 退回指定历史节点 */
	@RepeatSubmit
	@PostMapping("/task/reject-node/{taskId}")
	public R<String> rejectNode(@PathVariable Long taskId, @RequestBody RejectNodeParam param) {
		return R.data(flowService.rejectToNode(taskId, param.nodeCode(), param.message()));
	}

	/** 撤回（发起人撤回实例） */
	@RepeatSubmit
	@PostMapping("/task/revoke/{instanceId}")
	public R<String> revoke(@PathVariable Long instanceId, @RequestBody(required = false) ActionParam param) {
		return R.data(flowService.revoke(instanceId, msg(param)));
	}

	/** 作废（终止实例） */
	@RepeatSubmit
	@PostMapping("/task/terminate/{taskId}")
	public R<String> terminate(@PathVariable Long taskId, @RequestBody(required = false) ActionParam param) {
		return R.data(flowService.terminate(taskId, msg(param)));
	}

	/** 转办/委派/加签/减签 单入口 */
	@RepeatSubmit
	@PostMapping("/task/operation/{taskId}")
	public R<Void> operation(@PathVariable Long taskId, @RequestBody OperationParam param) {
		if (param == null) {
			throw new ServiceException("请先选择办理人。可以按用户名或昵称搜索");
		}
		return R.success(flowService.taskOperation(taskId, param.op(), param.handlers(), param.message()));
	}

	/** 抄送 */
	@RepeatSubmit
	@PostMapping("/task/copy/{taskId}")
	public R<Void> copy(@PathVariable Long taskId, @RequestBody CopyParam param) {
		if (param == null) {
			throw new ServiceException("请先选择抄送人。可以按用户名或昵称搜索");
		}
		return R.success(flowService.copyTo(taskId, param.userIds()));
	}

	private String msg(ActionParam param) {
		return param == null ? null : param.message();
	}

	private static String textOf(Object raw) {
		return raw == null ? null : String.valueOf(raw);
	}

	private static Long longOf(Object raw) {
		if (raw == null || String.valueOf(raw).isBlank()) {
			return null;
		}
		try {
			return Long.valueOf(String.valueOf(raw).trim());
		} catch (NumberFormatException e) {
			throw new ServiceException("请从列表中选择一条正在办理的流程");
		}
	}

	// ==================== 设计 JSON 组装 ====================

	/** 由设计生成 warm-flow 定义 JSON：开始→审批节点链→结束，横向布局 */
	private String buildFlowJson(FlowDesign req) throws Exception {
		List<Map<String, Object>> nodeList = new ArrayList<>();
		int n = req.nodes().size();
		int baseX = 100;
		int step = 160;
		int y = 200;
		List<String> codes = new ArrayList<>();
		codes.add("start");
		for (int i = 0; i < n; i++) {
			codes.add("approve" + (i + 1));
		}
		codes.add("end");

		int x = baseX;
		boolean hasForm = req.formKey() != null && !req.formKey().isBlank();
		Map<String, Object> startNode = node(0, "start", "开始", null, null, x, y, edge("start", codes.get(1), "提交", x, y, x + step));
		if (hasForm) {
			bindForm(startNode, req.formKey(), null);
		}
		nodeList.add(startNode);

		for (int i = 0; i < n; i++) {
			x = baseX + step * (i + 1);
			FlowDesignNode dn = req.nodes().get(i);
			String code = codes.get(i + 1);
			String next = codes.get(i + 2);
			String ratio = dn.nodeRatio() == null || dn.nodeRatio().isBlank() ? "0" : dn.nodeRatio();
			String permissionFlag = permissionFlag(dn);
			Map<String, Object> an = node(1, code, dn.name(), permissionFlag, ratio, x, y, edge(code, next, "通过", x, y, x + step));
			if (hasForm) {
				bindForm(an, req.formKey(), dn.fieldPerms());
			}
			nodeList.add(an);
		}

		x = baseX + step * (n + 1);
		Map<String, Object> end = node(2, "end", "结束", null, null, x, y, null);
		end.put("skipList", new ArrayList<>());
		nodeList.add(end);

		Map<String, Object> root = new LinkedHashMap<>();
		root.put("flowCode", req.flowCode());
		root.put("flowName", req.flowName());
		root.put("modelValue", "CLASSICS");
		root.put("version", "1");
		root.put("formCustom", "N");
		root.put("nodeList", nodeList);
		return objectMapper.writeValueAsString(root);
	}

	private Map<String, Object> node(int type, String code, String name, String permissionFlag, String nodeRatio,
									 int x, int y, Map<String, Object> edge) {
		Map<String, Object> node = new LinkedHashMap<>();
		node.put("nodeType", type);
		node.put("nodeCode", code);
		node.put("nodeName", name);
		if (permissionFlag != null) {
			node.put("permissionFlag", permissionFlag);
			node.put("nodeRatio", nodeRatio == null ? "0" : nodeRatio);
		}
		node.put("coordinate", x + "," + y + "|" + x + "," + y);
		if (edge != null) {
			List<Map<String, Object>> skips = new ArrayList<>();
			skips.add(edge);
			node.put("skipList", skips);
		}
		return node;
	}

	/** 绑定业务表单到节点：formCustom='Y' + formPath=表单 key；审批节点附字段权限到 ext(JSON) */
	private void bindForm(Map<String, Object> node, String formKey, Map<String, String> fieldPerms) throws Exception {
		node.put("formCustom", "Y");
		node.put("formPath", formKey);
		if (fieldPerms != null && !fieldPerms.isEmpty()) {
			node.put("ext", objectMapper.writeValueAsString(fieldPerms));
		}
	}

	private Map<String, Object> edge(String from, String to, String name, int x, int y, int nextX) {
		Map<String, Object> e = new LinkedHashMap<>();
		e.put("nowNodeCode", from);
		e.put("nextNodeCode", to);
		e.put("skipName", name);
		e.put("skipType", "PASS");
		e.put("coordinate", (x + 20) + "," + y + ";" + (nextX - 20) + "," + y);
		return e;
	}

	/** 节点候选人编码为 permissionFlag（warm-flow @@ 分隔多类型 storageId）；兼容旧 role 单角色写法 */
	private String permissionFlag(FlowDesignNode dn) {
		if (dn.candidates() != null && !dn.candidates().isEmpty()) {
			return dn.candidates().stream().filter(c -> c != null && !c.isBlank())
				.collect(java.util.stream.Collectors.joining(com.mugsun.boot.common.constant.FlowConstants.SEPARATOR));
		}
		if (dn.role() != null && !dn.role().isBlank()) {
			return "role:" + dn.role();
		}
		throw new ServiceException("审批节点「" + dn.name() + "」未配置候选人");
	}

	/** 流程设计请求：流程码/名 + 绑定业务表单 formKey(可空) + 审批节点链 */
	public record FlowDesign(String flowCode, String flowName, String formKey, List<FlowDesignNode> nodes) {
	}

	/**
	 * 审批节点：名称 + 候选人(多类型 storageId 前缀编码 role:/dept:/post:/user:/initiator/deptLeader) +
	 * 通过比例(0或签/100会签/1~99票签) + 字段级权限(字段名→READ/WRITE/NONE，办理时对绑定表单生效)；role 为旧单角色兼容位
	 */
	public record FlowDesignNode(String name, List<String> candidates, String role, String nodeRatio,
								 Map<String, String> fieldPerms) {
	}

	/** 发起参数：发起人自选的首个审批节点办理人 + 发起业务数据 */
	public record StartParam(List<String> handlers, Map<String, Object> variable) {
	}

	/** 通用动作参数（审批意见 + 可选办理阶段表单数据） */
	public record ActionParam(String message, Map<String, Object> variable) {
	}

	/** 退回指定节点参数 */
	public record RejectNodeParam(String nodeCode, String message) {
	}

	/** 转办/委派/加减签参数 */
	public record OperationParam(String op, List<String> handlers, String message) {
	}

	/** 抄送参数 */
	public record CopyParam(List<String> userIds) {
	}
}

