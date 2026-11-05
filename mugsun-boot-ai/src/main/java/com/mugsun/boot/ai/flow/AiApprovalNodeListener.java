package com.mugsun.boot.ai.flow;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mugsun.boot.ai.entity.AiApp;
import com.mugsun.boot.ai.mapper.AiAppMapper;
import com.mugsun.boot.common.constant.FlowConstants;
import com.mugsun.boot.tenant.TenantContext;
import com.mugsun.boot.system.service.HandlerSelectService;
import org.dromara.warm.flow.core.FlowEngine;
import org.dromara.warm.flow.core.dto.FlowParams;
import org.dromara.warm.flow.core.entity.Instance;
import org.dromara.warm.flow.core.entity.Node;
import org.dromara.warm.flow.core.entity.Task;
import org.dromara.warm.flow.core.listener.Listener;
import org.dromara.warm.flow.core.listener.ListenerVariable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * warm-flow 节点 create 监听器：调用已发布 AiFlow，写回流程变量与 his.ext aiTrace；
 * 成功 skip PASS；失败按 onFailure 走 rejectLast / transfer:role / stay。
 * <p>安全：工作线程 {@code TenantContext.execute(instance.tenantId)}；AiAppRun.tenantId 显式实例租户；
 * 强制 inputMap；outputMap 白名单；inputSummary 脱敏；timeout ≤ {@link AiApprovalConstants#MAX_TIMEOUT_MS}。
 */
@Component
public class AiApprovalNodeListener implements Listener {

	private static final Logger log = LoggerFactory.getLogger(AiApprovalNodeListener.class);

	private final ObjectMapper objectMapper;
	private final AiAppMapper appMapper;
	private final AiFlowExecutor flowExecutor;
	private final HandlerSelectService selectService;

	public AiApprovalNodeListener(ObjectMapper objectMapper, AiAppMapper appMapper,
								  AiFlowExecutor flowExecutor, HandlerSelectService selectService) {
		this.objectMapper = objectMapper;
		this.appMapper = appMapper;
		this.flowExecutor = flowExecutor;
		this.selectService = selectService;
	}

	@Override
	public void notify(ListenerVariable variable) {
		Task task = variable.getTask();
		Node node = variable.getNode();
		Instance instance = variable.getInstance();
		if (task == null || node == null || instance == null) {
			log.warn("[ai-approval] skip: missing task/node/instance");
			return;
		}
		long t0 = System.currentTimeMillis();
		AiApprovalNodeConfig cfg;
		try {
			cfg = AiApprovalNodeConfig.parse(objectMapper, node.getExt());
		} catch (Exception e) {
			fail(task, instance, variable, null, null, AiApprovalConstants.ERR_EXECUTE,
				"AI 节点配置无效", t0, AiApprovalConstants.ON_FAILURE_REJECT_LAST, Map.of());
			return;
		}
		if (cfg.aiFlowId() == null) {
			fail(task, instance, variable, null, null, AiApprovalConstants.ERR_APP_MISSING,
				"未绑定 AI 应用", t0, cfg.onFailure(), Map.of());
			return;
		}

		AiApprovalAppGate.GateResult gate = AiApprovalAppGate.load(
			appMapper, objectMapper, cfg.aiFlowId(), instance.getTenantId());
		if (!gate.ok()) {
			fail(task, instance, variable, cfg.aiFlowId(), null, gate.errorCode(), gate.errorMsg(),
				t0, cfg.onFailure(), Map.of());
			return;
		}
		AiApp app = gate.app();

		Map<String, Object> flowVars = variable.getVariable() == null ? Map.of() : variable.getVariable();
		Map<String, Object> input = buildInput(flowVars, cfg.inputMap());
		String inputSummary = summarizeInput(input);
		Long initiatorId = parseLong(instance.getCreateBy());

		String instanceTenant = instance.getTenantId();
		if (instanceTenant == null || instanceTenant.isBlank()) {
			fail(task, instance, variable, cfg.aiFlowId(), null, AiApprovalConstants.ERR_TENANT_DENIED,
				"流程实例缺少租户（fail-closed）", t0, cfg.onFailure(), inputSummaryMap(inputSummary));
			return;
		}
		long timeoutMs = AiApprovalConstants.clampTimeoutMs(cfg.timeoutMs());
		Map<String, Object> result;
		try {
			// 工作线程显式带租户 + AiAppRun.tenantId 显式写实例租户（禁止只靠 ambient）
			result = runWithTimeout(
				() -> flowExecutor.run(app, input, initiatorId, instanceTenant),
				timeoutMs,
				instanceTenant);
		} catch (TimeoutException te) {
			fail(task, instance, variable, cfg.aiFlowId(), null, AiApprovalConstants.ERR_TIMEOUT,
				"AI 执行超时", t0, cfg.onFailure(), inputSummaryMap(inputSummary));
			return;
		} catch (Exception e) {
			fail(task, instance, variable, cfg.aiFlowId(), null, AiApprovalConstants.ERR_EXECUTE,
				trimMsg(e.getMessage()), t0, cfg.onFailure(), inputSummaryMap(inputSummary));
			return;
		}

		Object runIdObj = result.get("runId");
		Long runId = runIdObj instanceof Number n ? n.longValue() : parseLong(String.valueOf(runIdObj));
		String status = String.valueOf(result.getOrDefault("status", ""));
		if (!"success".equalsIgnoreCase(status)) {
			fail(task, instance, variable, cfg.aiFlowId(), runId, AiApprovalConstants.ERR_EXECUTE,
				trimMsg(String.valueOf(result.get("errorMsg"))), t0, cfg.onFailure(),
				inputSummaryMap(inputSummary));
			return;
		}

		@SuppressWarnings("unchecked")
		Map<String, Object> vars = result.get("vars") instanceof Map<?, ?> m
			? new HashMap<>((Map<String, Object>) m) : new HashMap<>();
		Map<String, Object> merge = mapOutputs(vars, cfg.outputMap(), runId);
		Object summary = merge.get(FlowConstants.AI_VAR_SUMMARY);
		Object pass = merge.get(FlowConstants.AI_VAR_PASS);
		Object reason = merge.get(FlowConstants.AI_VAR_REASON);

		Map<String, Object> outputTrace = new LinkedHashMap<>();
		outputTrace.put(FlowConstants.AI_VAR_SUMMARY, summary);
		outputTrace.put(FlowConstants.AI_VAR_PASS, pass);
		outputTrace.put(FlowConstants.AI_VAR_REASON, reason);

		Map<String, Object> aiTrace = new LinkedHashMap<>();
		aiTrace.put("aiFlowId", cfg.aiFlowId());
		aiTrace.put("runId", runId);
		aiTrace.put("inputSummary", inputSummary);
		aiTrace.put("output", outputTrace);
		aiTrace.put("errorCode", null);
		aiTrace.put("errorMsg", null);
		aiTrace.put("durationMs", System.currentTimeMillis() - t0);

		try {
			String hisExt = objectMapper.writeValueAsString(aiTrace);
			FlowParams params = FlowParams.build()
				.skipType("PASS")
				.message("AI预审完成")
				.variable(merge)
				.hisTaskExt(hisExt)
				.ignore(true)
				.handler(instance.getCreateBy());
			FlowEngine.taskService().skip(task.getId(), params);
		} catch (Exception e) {
			log.warn("[ai-approval] skip PASS failed: {}", e.toString());
			fail(task, instance, variable, cfg.aiFlowId(), runId, AiApprovalConstants.ERR_EXECUTE,
				"写回流程失败: " + trimMsg(e.getMessage()), t0, cfg.onFailure(), inputSummaryMap(inputSummary));
		}
	}

	private void fail(Task task, Instance instance, ListenerVariable variable, Long aiFlowId, Long runId,
					  String code, String msg, long t0, String onFailure, Map<String, Object> extra) {
		Map<String, Object> merge = new LinkedHashMap<>();
		if (extra != null) {
			merge.putAll(extra);
		}
		merge.put(FlowConstants.AI_VAR_ERROR_CODE, code);
		merge.put(FlowConstants.AI_VAR_ERROR_MSG, msg == null ? "" : msg);
		if (runId != null) {
			merge.put(FlowConstants.AI_VAR_RUN_ID, runId);
		}
		Map<String, Object> aiTrace = new LinkedHashMap<>();
		aiTrace.put("aiFlowId", aiFlowId);
		aiTrace.put("runId", runId);
		aiTrace.put("inputSummary", extra == null ? null : extra.get("inputSummary"));
		aiTrace.put("output", null);
		aiTrace.put("errorCode", code);
		aiTrace.put("errorMsg", msg);
		aiTrace.put("durationMs", System.currentTimeMillis() - t0);
		String hisExt;
		try {
			hisExt = objectMapper.writeValueAsString(aiTrace);
		} catch (Exception e) {
			hisExt = "{\"errorCode\":\"" + code + "\"}";
		}
		try {
			FlowEngine.taskService().mergeVariable(instance, merge);
		} catch (Exception e) {
			log.warn("[ai-approval] mergeVariable failed: {}", e.toString());
		}
		String strategy = onFailure == null || onFailure.isBlank()
			? AiApprovalConstants.ON_FAILURE_REJECT_LAST : onFailure.trim();
		try {
			if (AiApprovalConstants.ON_FAILURE_STAY.equals(strategy)) {
				// 留待办；变量已含 ai_error_*；his 待人工办结后再出现
				return;
			}
			if (strategy.startsWith(AiApprovalConstants.ON_FAILURE_TRANSFER_ROLE_PREFIX)) {
				String role = strategy.substring(AiApprovalConstants.ON_FAILURE_TRANSFER_ROLE_PREFIX.length()).trim();
				List<String> people = selectService.byRole(role);
				if (people.isEmpty()) {
					log.warn("[ai-approval] transfer role {} empty → rejectLast", role);
					rejectLast(task, instance, msg, hisExt);
					return;
				}
				FlowParams p = FlowParams.build()
					.message("AI失败转办: " + code)
					.hisTaskExt(hisExt)
					.ignore(true)
					.handler(instance.getCreateBy())
					.addHandlers(people);
				FlowEngine.taskService().transfer(task.getId(), p);
				return;
			}
			rejectLast(task, instance, msg, hisExt);
		} catch (Exception e) {
			log.warn("[ai-approval] onFailure {} failed: {}", strategy, e.toString());
		}
	}

	private void rejectLast(Task task, Instance instance, String msg, String hisExt) {
		FlowParams p = FlowParams.build()
			.message(msg == null || msg.isBlank() ? "AI预审失败" : msg)
			.hisTaskExt(hisExt)
			.ignore(true)
			.handler(instance.getCreateBy());
		FlowEngine.taskService().rejectLast(task.getId(), p);
	}


	/**
	 * 安全收紧（相对初版「缺省浅拷贝全量流程变量」）：无 inputMap 或空 map = 无输入，不灌全量流程变量。
	 * 须在节点配置显式声明映射键。
	 */
	static Map<String, Object> buildInput(Map<String, Object> flowVars, Map<String, String> inputMap) {
		Map<String, Object> input = new LinkedHashMap<>();
		if (inputMap == null || inputMap.isEmpty()) {
			return input;
		}
		Map<String, Object> src = flowVars == null ? Map.of() : flowVars;
		for (Map.Entry<String, String> e : inputMap.entrySet()) {
			String flowKey = e.getKey();
			String aiKey = e.getValue() == null || e.getValue().isBlank() ? flowKey : e.getValue();
			if (src.containsKey(flowKey)) {
				input.put(aiKey, src.get(flowKey));
			}
		}
		return input;
	}

	/**
	 * 写回目标键白名单：ai_summary/ai_pass/ai_reason/ai_run_id 或 ai_ext_*；非法目标键静默丢弃。
	 */
	static Map<String, Object> mapOutputs(Map<String, Object> vars, Map<String, String> outputMap, Long runId) {
		Map<String, Object> merge = new LinkedHashMap<>();
		Map<String, Object> src = vars == null ? Map.of() : vars;
		if (outputMap != null && !outputMap.isEmpty()) {
			for (Map.Entry<String, String> e : outputMap.entrySet()) {
				String from = e.getKey();
				String to = e.getValue() == null || e.getValue().isBlank() ? from : e.getValue().trim();
				if (!AiApprovalConstants.isAllowedOutputKey(to)) {
					continue;
				}
				if (src.containsKey(from)) {
					merge.put(to, coerce(to, src.get(from)));
				}
			}
		} else {
			copyIfPresent(src, merge, FlowConstants.AI_VAR_SUMMARY);
			copyIfPresent(src, merge, FlowConstants.AI_VAR_PASS);
			copyIfPresent(src, merge, FlowConstants.AI_VAR_REASON);
		}
		if (!merge.containsKey(FlowConstants.AI_VAR_SUMMARY) && src.get("output") != null) {
			merge.put(FlowConstants.AI_VAR_SUMMARY, String.valueOf(src.get("output")));
		}
		if (runId != null) {
			merge.put(FlowConstants.AI_VAR_RUN_ID, runId);
		}
		return merge;
	}

	private static void copyIfPresent(Map<String, Object> from, Map<String, Object> to, String key) {
		if (from.containsKey(key)) {
			to.put(key, coerce(key, from.get(key)));
		}
	}

	private static Object coerce(String key, Object v) {
		if (FlowConstants.AI_VAR_PASS.equals(key) && !(v instanceof Boolean)) {
			String s = String.valueOf(v).trim();
			if ("true".equalsIgnoreCase(s) || "1".equals(s) || "yes".equalsIgnoreCase(s)) {
				return Boolean.TRUE;
			}
			if ("false".equalsIgnoreCase(s) || "0".equals(s) || "no".equalsIgnoreCase(s)) {
				return Boolean.FALSE;
			}
		}
		return v;
	}

	/** 脱敏：password/token/secret/authorization 等键名写 ***，值不落痕迹 */
	static String summarizeInput(Map<String, Object> input) {
		if (input == null || input.isEmpty()) {
			return "";
		}
		StringBuilder sb = new StringBuilder();
		int n = 0;
		for (Map.Entry<String, Object> e : input.entrySet()) {
			if (n++ >= 8) {
				sb.append("…");
				break;
			}
			if (sb.length() > 0) {
				sb.append("; ");
			}
			String key = e.getKey();
			if (AiApprovalConstants.isSensitiveInputKey(key)) {
				sb.append(key).append("=***");
				continue;
			}
			String v = String.valueOf(e.getValue());
			if (v.length() > 40) {
				v = v.substring(0, 40) + "…";
			}
			sb.append(key).append('=').append(v);
		}
		String s = sb.toString();
		return s.length() > 240 ? s.substring(0, 240) + "…" : s;
	}

	private static Map<String, Object> inputSummaryMap(String summary) {
		Map<String, Object> m = new LinkedHashMap<>();
		m.put("inputSummary", summary);
		return m;
	}

	private Map<String, Object> runWithTimeout(Callable<Map<String, Object>> call, long timeoutMs, String tenantId)
		throws Exception {
		return executeWithTenantTimeout(tenantId, timeoutMs, call);
	}

	/**
	 * 在独立工作线程执行，并用 {@link TenantContext#execute} 显式带上实例租户。
	 * package-visible 供单测验证线程租户传递。
	 */
	static <T> T executeWithTenantTimeout(String tenantId, long timeoutMs, Callable<T> call) throws Exception {
		ExecutorService es = Executors.newSingleThreadExecutor(r -> {
			Thread t = new Thread(r, "ai-approval-exec");
			t.setDaemon(true);
			return t;
		});
		Future<T> f = es.submit(() -> TenantContext.execute(tenantId, () -> {
			try {
				return call.call();
			} catch (RuntimeException re) {
				throw re;
			} catch (Exception e) {
				throw new RuntimeException(e);
			}
		}));
		try {
			return f.get(Math.max(1L, timeoutMs), TimeUnit.MILLISECONDS);
		} catch (TimeoutException te) {
			f.cancel(true);
			throw te;
		} catch (ExecutionException ee) {
			Throwable c = ee.getCause() == null ? ee : ee.getCause();
			if (c instanceof RuntimeException re && re.getCause() instanceof Exception nested
				&& !(nested instanceof RuntimeException)) {
				throw nested;
			}
			if (c instanceof Exception ex) {
				throw ex;
			}
			throw new RuntimeException(c);
		} finally {
			es.shutdownNow();
		}
	}

	private static Long parseLong(String s) {
		if (s == null || s.isBlank()) {
			return null;
		}
		try {
			return Long.valueOf(s.trim());
		} catch (NumberFormatException e) {
			return null;
		}
	}

	private static String trimMsg(String m) {
		if (m == null) {
			return "";
		}
		String t = m.trim();
		return t.length() > 200 ? t.substring(0, 200) : t;
	}
}
