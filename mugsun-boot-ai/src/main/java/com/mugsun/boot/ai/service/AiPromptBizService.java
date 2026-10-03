package com.mugsun.boot.ai.service;

import com.mugsun.boot.ai.AiConstants;
import com.mugsun.boot.ai.AiModuleService;
import com.mugsun.boot.ai.entity.AiModel;
import com.mugsun.boot.ai.entity.AiPrompt;
import com.mugsun.boot.ai.mapper.AiPromptMapper;
import com.mugsun.boot.ai.support.AiLlmClient;
import com.mugsun.boot.tenant.TenantContext;
import com.mugsun.core.tool.exception.ServiceException;
import com.mybatisflex.core.paginate.Page;
import com.mybatisflex.core.query.QueryWrapper;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

@Service
public class AiPromptBizService {

	private final AiModuleService moduleService;
	private final AiPromptMapper mapper;
	private final AiModelBizService modelBizService;
	private final AiLlmClient llmClient;

	public AiPromptBizService(AiModuleService moduleService, AiPromptMapper mapper,
							  AiModelBizService modelBizService, AiLlmClient llmClient) {
		this.moduleService = moduleService;
		this.mapper = mapper;
		this.modelBizService = modelBizService;
		this.llmClient = llmClient;
	}

	public Page<AiPrompt> page(long pageNum, long pageSize, String name, String category) {
		moduleService.requireEnabled();
		QueryWrapper q = QueryWrapper.create().orderBy("id", false);
		if (name != null && !name.isBlank()) {
			q.like("name", name.trim());
		}
		if (category != null && !category.isBlank()) {
			q.eq("category", category.trim());
		}
		return mapper.paginate(pageNum, pageSize, q);
	}

	public AiPrompt detail(Long id) {
		moduleService.requireEnabled();
		return require(id);
	}

	public AiPrompt submit(AiPrompt body) {
		moduleService.requireEnabled();
		if (body.getId() == null && (body.getName() == null || body.getName().isBlank())) {
			throw new ServiceException("请填写提示词名称");
		}
		if (body.getId() == null) {
			body.sanitizeForInsert();
			body.setTenantId(TenantContext.current());
			if (body.getVersion() == null) {
				body.setVersion(1);
			}
			if (body.getStatus() == null) {
				body.setStatus(AiConstants.STATUS_ENABLE);
			}
			mapper.insert(body);
		} else {
			AiPrompt db = require(body.getId());
			int ver = db.getVersion() == null ? 1 : db.getVersion();
			if (body.getName() == null || body.getName().isBlank()) {
				body.setName(db.getName());
			}
			body.sanitizeForUpdate();
			body.setTenantId(db.getTenantId());
			if (body.getContent() != null && !body.getContent().equals(db.getContent())) {
				body.setVersion(ver + 1);
			} else {
				body.setVersion(ver);
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

	public Map<String, Object> optimize(Long id) {
		moduleService.requireEnabled();
		AiPrompt p = require(id);
		AiModel model = modelBizService.requireDefaultChat();
		String source = p.getContent() == null ? "" : p.getContent();
		String improved = llmClient.chat(model, List.of(
			Map.of("role", "system", "content", "你是提示词工程师，请优化用户给出的提示词，只输出优化后的正文。"),
			Map.of("role", "user", "content", source)
		), 1024);
		int ver = p.getVersion() == null ? 1 : p.getVersion();
		return Map.of("content", improved, "version", ver);
	}

	private AiPrompt require(Long id) {
		AiPrompt p = mapper.selectOneById(id);
		if (p == null) {
			throw new ServiceException(AiConstants.MSG_PROMPT_MISSING);
		}
		return p;
	}
}
