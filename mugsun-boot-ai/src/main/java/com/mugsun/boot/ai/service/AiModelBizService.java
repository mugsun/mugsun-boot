package com.mugsun.boot.ai.service;

import cn.dev33.satoken.stp.StpUtil;
import com.mugsun.boot.ai.AiConstants;
import com.mugsun.boot.ai.AiModuleService;
import com.mugsun.boot.ai.entity.AiModel;
import com.mugsun.boot.ai.mapper.AiModelMapper;
import com.mugsun.boot.ai.support.AiLlmClient;
import com.mugsun.boot.tenant.TenantContext;
import com.mugsun.core.tool.exception.ServiceException;
import com.mybatisflex.core.paginate.Page;
import com.mybatisflex.core.query.QueryWrapper;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

@Service
public class AiModelBizService {

	private final AiModuleService moduleService;
	private final AiModelMapper modelMapper;
	private final AiLlmClient llmClient;

	public AiModelBizService(AiModuleService moduleService, AiModelMapper modelMapper, AiLlmClient llmClient) {
		this.moduleService = moduleService;
		this.modelMapper = modelMapper;
		this.llmClient = llmClient;
	}

	public Page<AiModel> page(long pageNum, long pageSize, String modelType, String name) {
		moduleService.requireEnabled();
		QueryWrapper q = QueryWrapper.create().orderBy("id", false);
		if (modelType != null && !modelType.isBlank()) {
			q.eq("model_type", modelType.trim());
		}
		if (name != null && !name.isBlank()) {
			q.like("model_name", name.trim());
		}
		Page<AiModel> page = modelMapper.paginate(pageNum, pageSize, q);
		page.getRecords().forEach(this::mask);
		return page;
	}

	public AiModel detail(Long id) {
		moduleService.requireEnabled();
		AiModel m = require(id);
		mask(m);
		return m;
	}

	public List<AiModel> listActive(String modelType) {
		moduleService.requireEnabled();
		QueryWrapper q = QueryWrapper.create()
			.eq("activate_flag", AiConstants.FLAG_YES)
			.orderBy("default_flag", false)
			.orderBy("id", false);
		if (modelType != null && !modelType.isBlank()) {
			q.eq("model_type", modelType.trim());
		}
		List<AiModel> list = modelMapper.selectListByQuery(q);
		list.forEach(this::mask);
		return list;
	}

	public AiModel submit(AiModel body) {
		moduleService.requireEnabled();
		if (body.getModelName() == null || body.getModelName().isBlank()) {
			throw new ServiceException("请填写模型名称");
		}
		if (body.getModelType() == null || body.getModelType().isBlank()) {
			throw new ServiceException("请选择模型类型");
		}
		if (body.getId() == null) {
			body.sanitizeForInsert();
			body.setTenantId(TenantContext.current());
			if (body.getActivateFlag() == null) {
				body.setActivateFlag(AiConstants.FLAG_NO);
			}
			if (body.getDefaultFlag() == null) {
				body.setDefaultFlag(AiConstants.FLAG_NO);
			}
			if (body.getBuiltinFlag() == null) {
				body.setBuiltinFlag(AiConstants.FLAG_NO);
			}
			if (body.getVisionFlag() == null) {
				body.setVisionFlag(AiConstants.FLAG_NO);
			}
			if (body.getApiKey() != null && !body.getApiKey().isBlank()) {
				boolean ok = llmClient.probe(body);
				body.setActivateFlag(ok ? AiConstants.FLAG_YES : AiConstants.FLAG_NO);
			}
			modelMapper.insert(body);
			mask(body);
			return body;
		}
		AiModel db = require(body.getId());
		String keepApi = db.getApiKey();
		String keepSecret = db.getSecretKey();
		body.sanitizeForUpdate();
		if (body.getApiKey() == null || body.getApiKey().isBlank()) {
			body.setApiKey(keepApi);
		}
		if (body.getSecretKey() == null || body.getSecretKey().isBlank()) {
			body.setSecretKey(keepSecret);
		}
		body.setTenantId(db.getTenantId());
		body.setBuiltinFlag(db.getBuiltinFlag());
		boolean ok = llmClient.probe(body);
		body.setActivateFlag(ok ? AiConstants.FLAG_YES : AiConstants.FLAG_NO);
		modelMapper.update(body);
		mask(body);
		return body;
	}

	public void remove(List<Long> ids) {
		moduleService.requireEnabled();
		if (ids == null || ids.isEmpty()) {
			return;
		}
		for (Long id : ids) {
			AiModel m = require(id);
			if (Integer.valueOf(AiConstants.FLAG_YES).equals(m.getBuiltinFlag())) {
				throw new ServiceException(AiConstants.MSG_BUILTIN_LOCKED);
			}
			modelMapper.deleteById(id);
		}
	}

	public void setDefault(Long id) {
		moduleService.requireEnabled();
		AiModel m = require(id);
		if (!Integer.valueOf(AiConstants.FLAG_YES).equals(m.getActivateFlag())) {
			throw new ServiceException(AiConstants.MSG_MODEL_INACTIVE);
		}
		List<AiModel> same = modelMapper.selectListByQuery(
			QueryWrapper.create().eq("model_type", m.getModelType()).eq("default_flag", AiConstants.FLAG_YES));
		for (AiModel x : same) {
			x.setDefaultFlag(AiConstants.FLAG_NO);
			x.sanitizeForUpdate();
			modelMapper.update(x);
		}
		m.setDefaultFlag(AiConstants.FLAG_YES);
		m.sanitizeForUpdate();
		modelMapper.update(m);
	}

	public Map<String, Object> test(Long id) {
		moduleService.requireEnabled();
		AiModel m = require(id);
		boolean ok = llmClient.probe(m);
		m.setActivateFlag(ok ? AiConstants.FLAG_YES : AiConstants.FLAG_NO);
		m.sanitizeForUpdate();
		modelMapper.update(m);
		return Map.of("ok", ok, "activateFlag", m.getActivateFlag());
	}

	/** 内部取默认 chat 模型（含密钥，不脱敏） */
	public AiModel requireDefaultChat() {
		moduleService.requireEnabled();
		AiModel m = modelMapper.selectOneByQuery(QueryWrapper.create()
			.eq("model_type", AiConstants.MODEL_TYPE_CHAT)
			.eq("activate_flag", AiConstants.FLAG_YES)
			.eq("default_flag", AiConstants.FLAG_YES)
			.limit(1));
		if (m == null) {
			m = modelMapper.selectOneByQuery(QueryWrapper.create()
				.eq("model_type", AiConstants.MODEL_TYPE_CHAT)
				.eq("activate_flag", AiConstants.FLAG_YES)
				.orderBy("id", false)
				.limit(1));
		}
		if (m == null) {
			throw new ServiceException("请先配置并激活默认对话模型");
		}
		return m;
	}

	/** 内部取默认 embedding 模型（含密钥） */
	public AiModel requireDefaultEmbedding() {
		moduleService.requireEnabled();
		AiModel m = modelMapper.selectOneByQuery(QueryWrapper.create()
			.eq("model_type", AiConstants.MODEL_TYPE_EMBEDDING)
			.eq("activate_flag", AiConstants.FLAG_YES)
			.eq("default_flag", AiConstants.FLAG_YES)
			.limit(1));
		if (m == null) {
			m = modelMapper.selectOneByQuery(QueryWrapper.create()
				.eq("model_type", AiConstants.MODEL_TYPE_EMBEDDING)
				.eq("activate_flag", AiConstants.FLAG_YES)
				.orderBy("id", false)
				.limit(1));
		}
		if (m == null) {
			throw new ServiceException("请先配置并激活默认向量模型");
		}
		return m;
	}

	public AiModel requireRaw(Long id) {
		return require(id);
	}

	private AiModel require(Long id) {
		AiModel m = modelMapper.selectOneById(id);
		if (m == null) {
			throw new ServiceException(AiConstants.MSG_MODEL_MISSING);
		}
		return m;
	}

	private void mask(AiModel m) {
		if (m == null) {
			return;
		}
		m.setApiKey(null);
		m.setSecretKey(null);
	}
}
