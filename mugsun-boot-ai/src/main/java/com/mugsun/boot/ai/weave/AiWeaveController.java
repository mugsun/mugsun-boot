package com.mugsun.boot.ai.weave;

import cn.dev33.satoken.annotation.SaCheckLogin;
import cn.dev33.satoken.annotation.SaCheckPermission;
import com.mugsun.boot.ai.AiModuleService;
import com.mugsun.boot.ai.entity.AiModel;
import com.mugsun.boot.ai.service.AiModelBizService;
import com.mugsun.boot.ai.support.AiLlmClient;
import com.mugsun.boot.common.module.ModuleInsightPort;
import com.mugsun.boot.gen.AiModelService;
import com.mugsun.core.tool.api.R;
import com.mugsun.core.tool.exception.ServiceException;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 平台织入只读工具端点；genDraft 委托现有规则建模（不改 GenModelingController 路径）。
 */
@RestController
@RequestMapping("/system/ai/weave")
@SaCheckLogin
public class AiWeaveController {

	private final AiModuleService moduleService;
	private final AiModelService genAiModelService;
	private final AiModelBizService modelBizService;
	private final AiLlmClient llmClient;
	private final ObjectProvider<ModuleInsightPort> insightPorts;

	public AiWeaveController(AiModuleService moduleService, AiModelService genAiModelService,
							 AiModelBizService modelBizService, AiLlmClient llmClient,
							 ObjectProvider<ModuleInsightPort> insightPorts) {
		this.moduleService = moduleService;
		this.genAiModelService = genAiModelService;
		this.modelBizService = modelBizService;
		this.llmClient = llmClient;
		this.insightPorts = insightPorts;
	}

	@PostMapping("/genDraft")
	@SaCheckPermission("sys:gen:list")
	public R<Map<String, Object>> genDraft(@RequestBody Map<String, String> body) {
		moduleService.requireEnabled();
		String description = body.getOrDefault("description", "");
		return R.data(genAiModelService.draft(description));
	}

	@PostMapping("/approvalSummary")
	@SaCheckLogin
	public R<Map<String, Object>> approvalSummary(@RequestBody Map<String, Object> body) {
		moduleService.requireEnabled();
		return R.data(ask("请对以下审批内容做要点与风险摘要（只读，勿建议改数据）：", body));
	}

	@PostMapping("/reportCharts")
	@SaCheckLogin
	public R<Map<String, Object>> reportCharts(@RequestBody Map<String, Object> body) {
		moduleService.requireEnabled();
		return R.data(ask("请根据描述给出 charts JSON 候选（只读候选，需人工确认）：", body));
	}

	@PostMapping("/gisAsk")
	@SaCheckLogin
	public R<Map<String, Object>> gisAsk(@RequestBody Map<String, Object> body) {
		return grounded("gis", "地理信息模块未装配，无法查询图层",
			"你是 GIS 只读助手。只能根据给出的库内图层实数解读，不要编造图层或要素数：", body);
	}

	@PostMapping("/trackInsight")
	@SaCheckLogin
	public R<Map<String, Object>> trackInsight(@RequestBody Map<String, Object> body) {
		return grounded("track", "埋点模块未装配，无法查询埋点数据",
			"请只根据给出的埋点库内实数解读，不要编造漏斗或留存：", body);
	}

	/**
	 * 先取可选模块的库内快照。模块不在 classpath 时直接业务失败，不调用模型。
	 * 有快照再尝试解读；缺模型时仍返回实数，并写明原因。
	 */
	private R<Map<String, Object>> grounded(String module, String missingMsg, String system, Map<String, Object> body) {
		moduleService.requireEnabled();
		ModuleInsightPort port = null;
		for (ModuleInsightPort candidate : insightPorts) {
			if (module.equals(candidate.module())) {
				port = candidate;
				break;
			}
		}
		if (port == null) {
			return R.fail(missingMsg);
		}
		Map<String, Object> snap;
		try {
			snap = port.snapshot(body == null ? Map.of() : body);
		} catch (ServiceException ex) {
			return R.fail(ex.getMessage());
		}
		Map<String, Object> out = new LinkedHashMap<>();
		out.put("grounded", true);
		out.put("data", snap);
		try {
			String question = body == null ? "" : String.valueOf(body.getOrDefault("content", ""));
			AiModel model = modelBizService.requireDefaultChat();
			String text = llmClient.chat(model, List.of(
				Map.of("role", "system", "content", system),
				Map.of("role", "user", "content", "库内实数：" + snap + "\n用户问题：" + question)
			), 1024);
			out.put("result", text);
		} catch (ServiceException ex) {
			out.put("result", "");
			out.put("modelMessage", ex.getMessage());
		} catch (RuntimeException ex) {
			out.put("result", "");
			out.put("modelMessage", ex.getMessage() == null ? "模型调用失败" : ex.getMessage());
		}
		return R.data(out);
	}

	private Map<String, Object> ask(String system, Map<String, Object> body) {
		AiModel model = modelBizService.requireDefaultChat();
		String user = body == null ? "" : String.valueOf(body.getOrDefault("content", body));
		String text = llmClient.chat(model, List.of(
			Map.of("role", "system", "content", system),
			Map.of("role", "user", "content", user)
		), 1024);
		return Map.of("result", text);
	}
}
