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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.Set;

@Service
public class AiPromptBizService {

	private final AiModuleService moduleService;
	private final AiPromptMapper mapper;
	private final AiModelBizService modelBizService;
	private static final Logger log = LoggerFactory.getLogger(AiPromptBizService.class);
	private static final Set<String> CATEGORIES = Set.of("chat", "content", "analyze", "creative");
	private static final String CATEGORY_HINT = "分类请选择对话类、内容生成类、分析处理类或创意设计类";

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
		if (pageNum < 1) {
			throw new ServiceException("页码请从 1 开始");
		}
		if (pageSize < 1 || pageSize > 100) {
			throw new ServiceException("每页条数请在 1 到 100 之间");
		}
		QueryWrapper q = QueryWrapper.create().orderBy("id", false);
		if (name != null && !name.isBlank()) {
			q.like("name", name.trim());
		}
		if (category != null && !category.isBlank()) {
			String value = category.trim();
			if (!CATEGORIES.contains(value)) {
				throw new ServiceException(CATEGORY_HINT);
			}
			q.eq("category", value);
		}
		return mapper.paginate(pageNum, pageSize, q);
	}

	public AiPrompt detail(Long id) {
		moduleService.requireEnabled();
		return require(id);
	}

	public AiPrompt submit(AiPrompt body) {
		moduleService.requireEnabled();
		if (body == null) {
			throw new ServiceException("请填写提示词名称。用来在列表里区分");
		}
		boolean create = body.getId() == null;
		AiPrompt db = create ? null : require(body.getId());
		String name = body.getName() == null ? "" : body.getName().trim();
		if (name.isEmpty()) {
			if (create) {
				throw new ServiceException("请填写提示词名称。用来在列表里区分");
			}
			name = db.getName();
		}
		if (name.length() > 64) {
			throw new ServiceException("提示词名称请控制在 64 个字以内");
		}
		String category = body.getCategory() == null ? "" : body.getCategory().trim();
		if (category.isEmpty() && !create) {
			category = db.getCategory() == null ? "" : db.getCategory();
		}
		if (!CATEGORIES.contains(category)) {
			throw new ServiceException(CATEGORY_HINT);
		}
		String scene = trimToNull(body.getScene());
		if (scene != null && scene.length() > 64) {
			throw new ServiceException("场景请控制在 64 个字以内");
		}
		String remark = trimToNull(body.getRemark());
		if (remark != null && remark.length() > 255) {
			throw new ServiceException("备注请控制在 255 个字以内");
		}
		if (body.getStatus() != null && body.getStatus() != 0 && body.getStatus() != 1) {
			throw new ServiceException("状态请选择启用或停用");
		}
		String content = body.getContent();
		if (content != null) {
			content = content.trim();
			if (content.isEmpty()) {
				throw new ServiceException("请填写提示词内容。优化和复制都会用这段正文");
			}
			if (content.length() > 8000) {
				throw new ServiceException("提示词内容请控制在 8000 个字以内");
			}
		} else if (create) {
			throw new ServiceException("请填写提示词内容。优化和复制都会用这段正文");
		}
		body.setName(name);
		body.setCategory(category);
		body.setScene(scene);
		body.setRemark(remark);
		body.setContent(content);
		requireUniqueName(name, body.getId());
		if (create) {
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
			int ver = db.getVersion() == null ? 1 : db.getVersion();
			body.sanitizeForUpdate();
			body.setTenantId(db.getTenantId());
			if (content != null && !content.equals(db.getContent())) {
				body.setVersion(ver + 1);
			} else {
				body.setVersion(ver);
			}
			mapper.update(body);
		}
		return body;
	}

	public List<AiPrompt> remove(List<Long> ids) {
		moduleService.requireEnabled();
		if (ids == null || ids.isEmpty()) {
			throw new ServiceException("请先选择要删除的提示词");
		}
		List<AiPrompt> rows = new java.util.ArrayList<>();
		for (Long id : ids) {
			if (id == null) {
				throw new ServiceException("提示词编号请填写数字，可以从列表里复制");
			}
			rows.add(require(id));
		}
		for (AiPrompt row : rows) {
			mapper.deleteById(row.getId());
		}
		return rows;
	}

	public Map<String, Object> optimize(Long id) {
		moduleService.requireEnabled();
		if (id == null) {
			throw new ServiceException("请先在列表里选择要优化的提示词");
		}
		AiPrompt p = require(id);
		String source = p.getContent() == null ? "" : p.getContent().trim();
		if (source.isEmpty()) {
			throw new ServiceException("这段提示词还没有正文。请先填写内容再优化");
		}
		AiModel model = modelBizService.requireDefaultChat();
		String improved;
		try {
			improved = llmClient.chat(model, List.of(
				Map.of("role", "system", "content", "你是提示词工程师，请优化用户给出的提示词，只输出优化后的正文。"),
				Map.of("role", "user", "content", source)
			), 1024);
		} catch (ServiceException e) {
			throw e;
		} catch (RuntimeException e) {
			log.warn("优化提示词失败: {}", e.getClass().getSimpleName());
			throw new ServiceException("这次没有优化成功。请确认默认对话模型已激活，稍后再试");
		}
		if (improved == null || improved.isBlank()) {
			throw new ServiceException("这次没有得到优化结果。请确认默认对话模型已激活，稍后再试");
		}
		int ver = p.getVersion() == null ? 1 : p.getVersion();
		return Map.of("content", improved, "version", ver);
	}

	private void requireUniqueName(String name, Long excludeId) {
		QueryWrapper q = QueryWrapper.create().eq("name", name);
		if (excludeId != null) {
			q.ne("id", excludeId);
		}
		if (mapper.selectCountByQuery(q) > 0) {
			throw new ServiceException("已经有叫「" + name + "」的提示词。请换一个名称，或打开原来那个");
		}
	}

	private static String trimToNull(String value) {
		if (value == null) {
			return null;
		}
		String trimmed = value.trim();
		return trimmed.isEmpty() ? null : trimmed;
	}

	private AiPrompt require(Long id) {
		AiPrompt p = mapper.selectOneById(id);
		if (p == null) {
			throw new ServiceException(AiConstants.MSG_PROMPT_MISSING);
		}
		return p;
	}
}
