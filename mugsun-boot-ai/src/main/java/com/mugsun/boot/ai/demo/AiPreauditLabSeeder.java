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
 * 实验室：幂等播种 AI 预审演示应用 + flowCode=ai-preaudit 定义。
 * 开关同 fronttest：{@code mugsun.lab.seed-fronttest=true}；生产不开。
 */
@Component
@Order(85)
public class AiPreauditLabSeeder implements ApplicationRunner {

	private static final Logger log = LoggerFactory.getLogger(AiPreauditLabSeeder.class);
	public static final String FLOW_CODE = "ai-preaudit";
	public static final String APP_NAME = "AI预审演示";

	private final boolean enabled;
	private final AiAppMapper appMapper;
	private final ObjectMapper objectMapper;

	public AiPreauditLabSeeder(@Value("${mugsun.lab.seed-fronttest:false}") boolean enabled,
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
			log.warn("[lab] ai-preaudit seed failed: {}", e.toString());
		}
	}

	private void seed() {
		var cntRow = Db.selectOneBySql(
			"select count(*) as \"c\" from flow_definition where flow_code = ? and coalesce(del_flag,'0') <> '1'",
			FLOW_CODE);
		long exists = cntRow == null || cntRow.get("c") == null ? 0L : ((Number) cntRow.get("c")).longValue();
		if (exists > 0) {
			log.debug("[lab] ai-preaudit seed skip：流程已存在");
			return;
		}
		AiApp app = appMapper.selectOneByQuery(QueryWrapper.create().eq("name", APP_NAME));
		if (app == null) {
			app = new AiApp();
			app.sanitizeForInsert();
			app.setTenantId("000000");
			app.setName(APP_NAME);
			app.setAppType("workflow");
			app.setDescription("审批内嵌演示：输出 ai_summary/ai_pass/ai_reason");
			app.setStatus(AiConstants.STATUS_ENABLE);
			app.setVersion(1);
			app.setDsl(demoDsl());
			appMapper.insert(app);
		}
		try {
			String json = new String(new ClassPathResource("flow/ai-preaudit.json").getInputStream()
				.readAllBytes(), StandardCharsets.UTF_8);
			Map<String, Object> ext = new LinkedHashMap<>();
			ext.put("aiFlowId", app.getId());
			ext.put("timeoutMs", 120000);
			ext.put("onFailure", "rejectLast");
			json = json.replace("\"__AI_EXT__\"", objectMapper.writeValueAsString(ext));
			Definition def = FlowEngine.defService().importJson(json);
			FlowEngine.defService().publish(def.getId());
			log.info("[lab] ai-preaudit seeded defId={} appId={}", def.getId(), app.getId());
		} catch (Exception e) {
			throw new IllegalStateException(e);
		}
	}

	/** 无 LLM / 禁能力节点：仅 updateVar 写出三键 */
	static String demoDsl() {
		return "{\"nodes\":["
			+ "{\"id\":\"start\",\"type\":\"start\",\"position\":{\"x\":80,\"y\":120},"
			+ "\"data\":{\"type\":\"start\",\"label\":\"开始\"}},"
			+ "{\"id\":\"u1\",\"type\":\"updateVar\",\"position\":{\"x\":300,\"y\":120},"
			+ "\"data\":{\"type\":\"updateVar\",\"label\":\"预审结论\",\"operations\":["
			+ "{\"target\":\"ai_summary\",\"action\":\"set\",\"value\":\"材料齐全，建议通过\"},"
			+ "{\"target\":\"ai_pass\",\"action\":\"set\",\"value\":\"true\"},"
			+ "{\"target\":\"ai_reason\",\"action\":\"set\",\"value\":\"演示预审自动通过\"}"
			+ "]}},"
			+ "{\"id\":\"end\",\"type\":\"end\",\"position\":{\"x\":520,\"y\":120},"
			+ "\"data\":{\"type\":\"end\",\"label\":\"结束\"}}"
			+ "],\"edges\":["
			+ "{\"id\":\"e1\",\"source\":\"start\",\"target\":\"u1\"},"
			+ "{\"id\":\"e2\",\"source\":\"u1\",\"target\":\"end\"}"
			+ "]}";
	}
}
