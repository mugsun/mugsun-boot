package com.mugsun.boot.ai.service;

import cn.hutool.core.util.IdUtil;
import com.mugsun.boot.ai.AiConstants;
import com.mugsun.boot.ai.AiModuleService;
import com.mugsun.boot.ai.entity.AiApp;
import com.mugsun.boot.ai.entity.AiAppNodeRun;
import com.mugsun.boot.ai.entity.AiAppRun;
import com.mugsun.boot.ai.flow.AiFlowExecutor;
import com.mugsun.boot.ai.mapper.AiAppMapper;
import com.mugsun.boot.ai.mapper.AiAppNodeRunMapper;
import com.mugsun.boot.ai.mapper.AiAppRunMapper;
import com.mugsun.boot.tenant.TenantContext;
import com.mugsun.core.tool.exception.ServiceException;
import com.mybatisflex.core.paginate.Page;
import com.mybatisflex.core.query.QueryWrapper;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Service
public class AiAppBizService {

	private final AiModuleService moduleService;
	private final AiAppMapper mapper;
	private final AiAppRunMapper runMapper;
	private final AiAppNodeRunMapper nodeRunMapper;
	private final AiFlowExecutor flowExecutor;

	public AiAppBizService(AiModuleService moduleService, AiAppMapper mapper, AiAppRunMapper runMapper,
						   AiAppNodeRunMapper nodeRunMapper, AiFlowExecutor flowExecutor) {
		this.moduleService = moduleService;
		this.mapper = mapper;
		this.runMapper = runMapper;
		this.nodeRunMapper = nodeRunMapper;
		this.flowExecutor = flowExecutor;
	}

	public Page<AiApp> page(long pageNum, long pageSize, String name, String appType) {
		moduleService.requireEnabled();
		QueryWrapper q = QueryWrapper.create().orderBy("id", false);
		if (name != null && !name.isBlank()) {
			q.like("name", name.trim());
		}
		if (appType != null && !appType.isBlank()) {
			q.eq("app_type", appType.trim());
		}
		return mapper.paginate(pageNum, pageSize, q);
	}

	public AiApp detail(Long id) {
		moduleService.requireEnabled();
		return require(id);
	}

	public AiApp submit(AiApp body) {
		moduleService.requireEnabled();
		if (body.getName() == null || body.getName().isBlank()) {
			throw new ServiceException("请填写应用名称");
		}
		if (body.getId() == null) {
			body.sanitizeForInsert();
			body.setTenantId(TenantContext.current());
			if (body.getStatus() == null) {
				body.setStatus(AiConstants.STATUS_ENABLE);
			}
			if (body.getVersion() == null) {
				body.setVersion(1);
			}
			if (body.getDsl() == null || body.getDsl().isBlank()) {
				body.setDsl(defaultDsl(body.getAppType()));
			}
			mapper.insert(body);
		} else {
			AiApp db = require(body.getId());
			body.sanitizeForUpdate();
			body.setTenantId(db.getTenantId());
			if (body.getDsl() != null && !body.getDsl().equals(db.getDsl())) {
				body.setVersion((db.getVersion() == null ? 1 : db.getVersion()) + 1);
			}
			mapper.update(body);
		}
		return body;
	}

	public void remove(List<Long> ids) {
		moduleService.requireEnabled();
		for (Long id : ids) {
			require(id);
			mapper.deleteById(id);
		}
	}

	public AiApp copy(Long id) {
		moduleService.requireEnabled();
		AiApp src = require(id);
		AiApp copy = new AiApp();
		copy.sanitizeForInsert();
		copy.setTenantId(TenantContext.current());
		copy.setName(src.getName() + " 副本");
		copy.setAppType(src.getAppType());
		copy.setDescription(src.getDescription());
		copy.setDsl(src.getDsl());
		copy.setModelId(src.getModelId());
		copy.setStatus(AiConstants.STATUS_ENABLE);
		copy.setVersion(1);
		mapper.insert(copy);
		return copy;
	}

	public AiApp saveDsl(Long id, String dsl) {
		moduleService.requireEnabled();
		AiApp app = require(id);
		String payload = dsl;
		if (payload == null || payload.isBlank()) {
			throw new ServiceException("DSL 不能为空");
		}
		app.setDsl(payload);
		app.setVersion((app.getVersion() == null ? 1 : app.getVersion()) + 1);
		app.sanitizeForUpdate();
		mapper.update(app);
		return app;
	}

	public Map<String, Object> run(Long id, Map<String, Object> input) {
		moduleService.requireEnabled();
		return flowExecutor.run(require(id), input == null ? Map.of() : input);
	}

	public Map<String, Object> share(Long id, boolean enable) {
		moduleService.requireEnabled();
		AiApp app = require(id);
		if (enable) {
			if (app.getShareToken() == null || app.getShareToken().isBlank()) {
				app.setShareToken(IdUtil.fastSimpleUUID());
			}
			app.setShareStatus(AiConstants.FLAG_YES);
		} else {
			app.setShareStatus(AiConstants.FLAG_NO);
		}
		app.sanitizeForUpdate();
		mapper.update(app);
		Map<String, Object> resp = new HashMap<>();
		resp.put("shareToken", app.getShareToken());
		resp.put("shareStatus", app.getShareStatus());
		resp.put("url", "/ai/share/" + app.getShareToken());
		return resp;
	}

	public String exportDsl(Long id) {
		moduleService.requireEnabled();
		return require(id).getDsl();
	}

	public AiApp importDsl(String name, String appType, String dsl) {
		moduleService.requireEnabled();
		AiApp app = new AiApp();
		app.sanitizeForInsert();
		app.setTenantId(TenantContext.current());
		app.setName(name == null || name.isBlank() ? "导入应用" : name);
		app.setAppType(appType == null || appType.isBlank() ? "workflow" : appType);
		app.setDsl(dsl);
		app.setStatus(AiConstants.STATUS_ENABLE);
		app.setVersion(1);
		mapper.insert(app);
		return app;
	}

	public Page<AiAppRun> runPage(long pageNum, long pageSize, Long appId) {
		moduleService.requireEnabled();
		QueryWrapper q = QueryWrapper.create().orderBy("id", false);
		if (appId != null) {
			q.eq("app_id", appId);
		}
		return runMapper.paginate(pageNum, pageSize, q);
	}

	public Map<String, Object> runDetail(Long runId) {
		moduleService.requireEnabled();
		AiAppRun run = runMapper.selectOneById(runId);
		if (run == null) {
			throw new ServiceException("执行记录不存在");
		}
		List<AiAppNodeRun> nodes = nodeRunMapper.selectListByQuery(
			QueryWrapper.create().eq("run_id", runId).orderBy("id", true));
		Map<String, Object> resp = new HashMap<>();
		resp.put("run", run);
		resp.put("nodes", nodes);
		return resp;
	}

	private String defaultDsl(String appType) {
		if ("text".equalsIgnoreCase(appType)) {
			return "{\"nodes\":["
				+ "{\"id\":\"start\",\"type\":\"start\",\"position\":{\"x\":80,\"y\":120},\"data\":{\"type\":\"start\",\"label\":\"开始\"}},"
				+ "{\"id\":\"text1\",\"type\":\"text\",\"position\":{\"x\":280,\"y\":120},\"data\":{\"type\":\"text\",\"label\":\"文本\",\"text\":\"{{input}}\"}},"
				+ "{\"id\":\"end\",\"type\":\"end\",\"position\":{\"x\":480,\"y\":120},\"data\":{\"type\":\"end\",\"label\":\"结束\"}}"
				+ "],\"edges\":[{\"id\":\"e1\",\"source\":\"start\",\"target\":\"text1\"},{\"id\":\"e2\",\"source\":\"text1\",\"target\":\"end\"}]}";
		}
		if ("chat".equalsIgnoreCase(appType)) {
			return "{\"mode\":\"chat\",\"chat\":{\"temperature\":0.7,\"maxTokens\":2000,\"topP\":1,\"frequencyPenalty\":0,\"systemPrompt\":\"\",\"greeting\":\"\",\"presets\":[]},\"nodes\":[],\"edges\":[]}";
		}
		return "{\"nodes\":["
			+ "{\"id\":\"start\",\"type\":\"start\",\"position\":{\"x\":80,\"y\":160},\"data\":{\"type\":\"start\",\"label\":\"开始\"}},"
			+ "{\"id\":\"llm1\",\"type\":\"llm\",\"position\":{\"x\":300,\"y\":160},\"data\":{\"type\":\"llm\",\"label\":\"大模型\",\"prompt\":\"{{input}}\"}},"
			+ "{\"id\":\"end\",\"type\":\"end\",\"position\":{\"x\":520,\"y\":160},\"data\":{\"type\":\"end\",\"label\":\"结束\"}}"
			+ "],\"edges\":[{\"id\":\"e1\",\"source\":\"start\",\"target\":\"llm1\"},{\"id\":\"e2\",\"source\":\"llm1\",\"target\":\"end\"}]}";
	}

	private AiApp require(Long id) {
		AiApp a = mapper.selectOneById(id);
		if (a == null) {
			throw new ServiceException(AiConstants.MSG_APP_MISSING);
		}
		return a;
	}
}
