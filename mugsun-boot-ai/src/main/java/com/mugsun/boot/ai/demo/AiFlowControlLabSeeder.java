package com.mugsun.boot.ai.demo;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mugsun.boot.ai.AiConstants;
import com.mugsun.boot.ai.entity.AiApp;
import com.mugsun.boot.ai.mapper.AiAppMapper;
import com.mugsun.boot.tenant.TenantContext;
import com.mybatisflex.core.query.QueryWrapper;
import com.mybatisflex.core.row.Db;
import org.dromara.warm.flow.core.FlowEngine;
import org.dromara.warm.flow.core.entity.Definition;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 实验室：幂等播种 foreach/parallel 演示应用 + 审批兼容流 ai-foreach-preaudit。
 * 开关同 fronttest：{@code mugsun.lab.seed-fronttest=true}。
 */
@Component
@Order(86)
public class AiFlowControlLabSeeder implements ApplicationRunner {

	private static final Logger log = LoggerFactory.getLogger(AiFlowControlLabSeeder.class);

	public static final String APP_FOREACH = "数组迭代演示";
	public static final String APP_PARALLEL = "两路并行演示";
	public static final String APP_APPROVAL = "迭代预审演示";
	public static final String FLOW_CODE = "ai-foreach-preaudit";

	private final boolean enabled;
	private final AiAppMapper appMapper;
	private final ObjectMapper objectMapper;

	public AiFlowControlLabSeeder(@Value("${mugsun.lab.seed-fronttest:false}") boolean enabled,
								  AiAppMapper appMapper, ObjectMapper objectMapper) {
		this.enabled = enabled;
		this.appMapper = appMapper;
		this.objectMapper = objectMapper;
	}

	@Override
	public void run(ApplicationArguments args) {
		if (!enabled) {
			return;
		}
		try {
			TenantContext.execute("000000", this::seed);
		} catch (Exception e) {
			log.warn("[lab] ai-flow-control seed failed: {}", e.toString());
		}
	}

	private void seed() {
		ensureApp(APP_FOREACH, "直跑：foreach+text 逐条输出", dslForeach());
		ensureApp(APP_PARALLEL, "直跑：parallel 两路汇合", dslParallel());
		AiApp approvalApp = ensureApp(APP_APPROVAL,
			"审批内嵌：foreach+updateVar 写 ai_summary/pass/reason", dslApprovalForeach());
		seedApprovalFlow(approvalApp);
	}

	private AiApp ensureApp(String name, String desc, String dsl) {
		AiApp app = appMapper.selectOneByQuery(QueryWrapper.create().eq("name", name));
		if (app != null) {
			return app;
		}
		app = new AiApp();
		app.sanitizeForInsert();
		app.setTenantId("000000");
		app.setName(name);
		app.setAppType("workflow");
		app.setDescription(desc);
		app.setStatus(AiConstants.STATUS_ENABLE);
		app.setVersion(1);
		app.setDsl(dsl);
		appMapper.insert(app);
		log.info("[lab] seeded AiApp name={} id={}", name, app.getId());
		return app;
	}

	private void seedApprovalFlow(AiApp app) {
		var cntRow = Db.selectOneBySql(
			"select count(*) as \"c\" from flow_definition where flow_code = ? and coalesce(del_flag,'0') <> '1'",
			FLOW_CODE);
		long exists = cntRow == null || cntRow.get("c") == null ? 0L : ((Number) cntRow.get("c")).longValue();
		if (exists > 0) {
			log.debug("[lab] {} seed skip：流程已存在", FLOW_CODE);
			return;
		}
		try {
			String json = new String(new ClassPathResource("flow/ai-foreach-preaudit.json").getInputStream()
				.readAllBytes(), StandardCharsets.UTF_8);
			Map<String, Object> ext = new LinkedHashMap<>();
			ext.put("aiFlowId", app.getId());
			ext.put("timeoutMs", 120000);
			ext.put("onFailure", "rejectLast");
			Map<String, String> inputMap = new LinkedHashMap<>();
			inputMap.put("items", "items");
			ext.put("inputMap", inputMap);
			json = json.replace("\"__AI_EXT__\"", objectMapper.writeValueAsString(ext));
			Definition def = FlowEngine.defService().importJson(json);
			FlowEngine.defService().publish(def.getId());
			log.info("[lab] {} seeded defId={} appId={}", FLOW_CODE, def.getId(), app.getId());
		} catch (Exception e) {
			throw new IllegalStateException(e);
		}
	}

	/** A：foreach + text（无禁节点，亦可绑审批） */
	static String dslForeach() {
		return "{\"nodes\":["
			+ "{\"id\":\"start\",\"type\":\"start\",\"position\":{\"x\":80,\"y\":120},"
			+ "\"data\":{\"type\":\"start\",\"label\":\"开始\"}},"
			+ "{\"id\":\"fe\",\"type\":\"foreach\",\"position\":{\"x\":280,\"y\":120},"
			+ "\"data\":{\"type\":\"foreach\",\"label\":\"逐条\",\"inputPath\":\"items\","
			+ "\"bodyNodeIds\":[\"t1\"],\"outputVar\":\"foreachResults\",\"aggregate\":\"array\","
			+ "\"maxIterations\":20}},"
			+ "{\"id\":\"t1\",\"type\":\"text\",\"position\":{\"x\":280,\"y\":260},"
			+ "\"data\":{\"type\":\"text\",\"label\":\"条目\",\"text\":\"条目:{{item}}\"}},"
			+ "{\"id\":\"end\",\"type\":\"end\",\"position\":{\"x\":520,\"y\":120},"
			+ "\"data\":{\"type\":\"end\",\"label\":\"结束\",\"output\":\"{{foreachResults}}\"}}"
			+ "],\"edges\":["
			+ "{\"id\":\"e1\",\"source\":\"start\",\"target\":\"fe\"},"
			+ "{\"id\":\"e2\",\"source\":\"fe\",\"target\":\"end\"}"
			+ "]}";
	}

	/** B：两路 parallel + 下游可读两边 */
	static String dslParallel() {
		return "{\"nodes\":["
			+ "{\"id\":\"start\",\"type\":\"start\",\"position\":{\"x\":80,\"y\":160},"
			+ "\"data\":{\"type\":\"start\",\"label\":\"开始\"}},"
			+ "{\"id\":\"p\",\"type\":\"parallel\",\"position\":{\"x\":280,\"y\":160},"
			+ "\"data\":{\"type\":\"parallel\",\"label\":\"并行\",\"branches\":["
			+ "{\"id\":\"b1\",\"label\":\"抽取\",\"bodyNodeIds\":[\"t1\"],\"outputVar\":\"branchExtract\"},"
			+ "{\"id\":\"b2\",\"label\":\"摘要\",\"bodyNodeIds\":[\"t2\"],\"outputVar\":\"branchSummary\"}"
			+ "]}},"
			+ "{\"id\":\"t1\",\"type\":\"text\",\"position\":{\"x\":200,\"y\":320},"
			+ "\"data\":{\"type\":\"text\",\"text\":\"EXTRACT\"}},"
			+ "{\"id\":\"t2\",\"type\":\"text\",\"position\":{\"x\":360,\"y\":320},"
			+ "\"data\":{\"type\":\"text\",\"text\":\"SUMMARY\"}},"
			+ "{\"id\":\"join\",\"type\":\"text\",\"position\":{\"x\":520,\"y\":160},"
			+ "\"data\":{\"type\":\"text\",\"text\":\"{{branchExtract}}|{{branchSummary}}\",\"outputVar\":\"joined\"}},"
			+ "{\"id\":\"end\",\"type\":\"end\",\"position\":{\"x\":720,\"y\":160},"
			+ "\"data\":{\"type\":\"end\",\"output\":\"{{joined}}\"}}"
			+ "],\"edges\":["
			+ "{\"id\":\"e1\",\"source\":\"start\",\"target\":\"p\"},"
			+ "{\"id\":\"e2\",\"source\":\"p\",\"target\":\"join\"},"
			+ "{\"id\":\"e3\",\"source\":\"join\",\"target\":\"end\"}"
			+ "]}";
	}

	/** C：foreach 后 updateVar 写审批三键（无禁节点） */
	static String dslApprovalForeach() {
		return "{\"nodes\":["
			+ "{\"id\":\"start\",\"type\":\"start\",\"position\":{\"x\":80,\"y\":120},"
			+ "\"data\":{\"type\":\"start\"}},"
			+ "{\"id\":\"fe\",\"type\":\"foreach\",\"position\":{\"x\":260,\"y\":120},"
			+ "\"data\":{\"type\":\"foreach\",\"inputPath\":\"items\",\"bodyNodeIds\":[\"t1\"],"
			+ "\"outputVar\":\"foreachResults\",\"aggregate\":\"array\",\"maxIterations\":20}},"
			+ "{\"id\":\"t1\",\"type\":\"text\",\"position\":{\"x\":260,\"y\":260},"
			+ "\"data\":{\"type\":\"text\",\"text\":\"{{item}}\"}},"
			+ "{\"id\":\"u1\",\"type\":\"updateVar\",\"position\":{\"x\":480,\"y\":120},"
			+ "\"data\":{\"type\":\"updateVar\",\"operations\":["
			+ "{\"target\":\"ai_summary\",\"action\":\"set\",\"value\":\"已逐条预审 {{foreachResults}}\"},"
			+ "{\"target\":\"ai_pass\",\"action\":\"set\",\"value\":\"true\"},"
			+ "{\"target\":\"ai_reason\",\"action\":\"set\",\"value\":\"foreach 演示自动通过\"}"
			+ "]}},"
			+ "{\"id\":\"end\",\"type\":\"end\",\"position\":{\"x\":700,\"y\":120},"
			+ "\"data\":{\"type\":\"end\"}}"
			+ "],\"edges\":["
			+ "{\"id\":\"e1\",\"source\":\"start\",\"target\":\"fe\"},"
			+ "{\"id\":\"e2\",\"source\":\"fe\",\"target\":\"u1\"},"
			+ "{\"id\":\"e3\",\"source\":\"u1\",\"target\":\"end\"}"
			+ "]}";
	}
}
