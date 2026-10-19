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
import java.util.Set;

@Service
public class AiModelBizService {

	private final AiModuleService moduleService;
	private final AiModelMapper modelMapper;
	private final AiLlmClient llmClient;
	private final com.mugsun.boot.ai.support.AiOutboundProperties outbound;

	public AiModelBizService(AiModuleService moduleService, AiModelMapper modelMapper, AiLlmClient llmClient,
							 com.mugsun.boot.ai.support.AiOutboundProperties outbound) {
		this.outbound = outbound;
		this.moduleService = moduleService;
		this.modelMapper = modelMapper;
		this.llmClient = llmClient;
	}

	private static final Set<String> TYPES = Set.of("chat", "embedding", "image", "audio");
	private static final Set<String> PROVIDERS = Set.of(
		"openai", "deepseek", "anthropic", "siliconflow", "volcengine", "ollama", "qianfan", "zhipu", "qwen", "custom");
	private static final String TYPE_HINT = "类型请选择对话、向量、图像或音频";
	private static final String PROVIDER_HINT = "提供商请从列表里选一个";

	public Page<AiModel> page(long pageNum, long pageSize, String modelType, String name) {
		moduleService.requireEnabled();
		if (pageNum < 1) {
			throw new ServiceException("页码请从 1 开始");
		}
		if (pageSize < 1 || pageSize > 100) {
			throw new ServiceException("每页条数请在 1 到 100 之间");
		}
		QueryWrapper q = QueryWrapper.create().orderBy("id", false);
		if (modelType != null && !modelType.isBlank()) {
			String type = modelType.trim();
			if (!TYPES.contains(type)) {
				throw new ServiceException(TYPE_HINT);
			}
			q.eq("model_type", type);
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
		if (body == null) {
			throw new ServiceException("请填写模型名称。用来在列表里区分");
		}
		String name = body.getModelName() == null ? "" : body.getModelName().trim();
		if (name.isEmpty()) {
			throw new ServiceException("请填写模型名称。用来在列表里区分");
		}
		if (name.length() > 64) {
			throw new ServiceException("模型名称请控制在 64 个字以内");
		}
		String type = body.getModelType() == null ? "" : body.getModelType().trim();
		if (!TYPES.contains(type)) {
			throw new ServiceException(TYPE_HINT);
		}
		String provider = body.getProvider() == null ? "" : body.getProvider().trim();
		if (!PROVIDERS.contains(provider)) {
			throw new ServiceException(PROVIDER_HINT);
		}
		String baseUrl = body.getBaseUrl() == null ? "" : body.getBaseUrl().trim();
		if (baseUrl.isEmpty()) {
			throw new ServiceException("请填写接口地址。需要以 http:// 或 https:// 开头");
		}
		if (baseUrl.length() > 255 || !(baseUrl.startsWith("http://") || baseUrl.startsWith("https://"))
			|| baseUrl.contains(" ") || baseUrl.contains("<")) {
			throw new ServiceException("接口地址需要以 http:// 或 https:// 开头，并且不要带空格");
		}
		try {
			outbound.guard(baseUrl);
		} catch (ServiceException e) {
			throw new ServiceException("接口地址不允许访问内网、元数据地址或非常用端口。内网自部署模型请让平台管理员把主机加入出站白名单");
		}
		String code = body.getModelCode() == null ? "" : body.getModelCode().trim();
		if (!code.matches("[A-Za-z0-9][A-Za-z0-9._:-]{0,63}")) {
			throw new ServiceException("模型标识请用字母或数字开头，只能包含字母、数字、点、下划线、短横线或冒号");
		}
		String remark = body.getRemark() == null ? null : body.getRemark().trim();
		if (remark != null && remark.length() > 255) {
			throw new ServiceException("备注请控制在 255 个字以内");
		}
		if ("embedding".equals(type)) {
			Integer dimensions = body.getDimensions();
			if (dimensions == null || dimensions < 1 || dimensions > 8192) {
				throw new ServiceException("向量维度请填 1 到 8192 之间的整数");
			}
		}
		body.setModelName(name);
		body.setModelType(type);
		body.setProvider(provider);
		body.setBaseUrl(baseUrl);
		body.setModelCode(code);
		body.setRemark(remark == null || remark.isEmpty() ? null : remark);
		requireUniqueName(name, body.getId());
		if (body.getId() == null) {
			body.sanitizeForInsert();
			body.setTenantId(TenantContext.current());
			if (body.getActivateFlag() == null) {
				body.setActivateFlag(AiConstants.FLAG_NO);
			}
			if (body.getDefaultFlag() == null) {
				body.setDefaultFlag(AiConstants.FLAG_NO);
			}
			body.setBuiltinFlag(AiConstants.FLAG_NO);
			if (body.getVisionFlag() == null) {
				body.setVisionFlag(AiConstants.FLAG_NO);
			}
			if (body.getApiKey() != null && !body.getApiKey().isBlank()) {
				boolean ok = llmClient.probe(body);
				body.setActivateFlag(ok ? AiConstants.FLAG_YES : AiConstants.FLAG_NO);
			} else {
				body.setActivateFlag(AiConstants.FLAG_NO);
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

	public List<AiModel> remove(List<Long> ids) {
		moduleService.requireEnabled();
		if (ids == null || ids.isEmpty()) {
			throw new ServiceException("请先选择要删除的模型");
		}
		List<AiModel> rows = new java.util.ArrayList<>();
		for (Long id : ids) {
			if (id == null) {
				throw new ServiceException("模型编号请填写数字，可以从列表里复制");
			}
			AiModel m = require(id);
			if (Integer.valueOf(AiConstants.FLAG_YES).equals(m.getBuiltinFlag())) {
				throw new ServiceException("内置模型不能删除。可以改配置，或另外添加一个模型");
			}
			rows.add(m);
		}
		for (AiModel row : rows) {
			modelMapper.deleteById(row.getId());
		}
		return rows;
	}

	public void setDefault(Long id) {
		moduleService.requireEnabled();
		if (id == null) {
			throw new ServiceException("请先在列表里选择要设为默认的模型");
		}
		AiModel m = require(id);
		if (!Integer.valueOf(AiConstants.FLAG_YES).equals(m.getActivateFlag())) {
			throw new ServiceException("这个模型还没激活。请先测试通过后再设为默认");
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
		if (id == null) {
			throw new ServiceException("请先在列表里选择要测试的模型");
		}
		AiModel m = require(id);
		boolean ok = llmClient.probe(m);
		m.setActivateFlag(ok ? AiConstants.FLAG_YES : AiConstants.FLAG_NO);
		m.sanitizeForUpdate();
		modelMapper.update(m);
		Map<String, Object> resp = new java.util.HashMap<>();
		resp.put("ok", ok);
		resp.put("activateFlag", m.getActivateFlag());
		resp.put("message", ok
			? "已连通。这个模型可以设为默认"
			: "这次没有连上。请检查地址和密钥后再试");
		return resp;
	}

	private void requireUniqueName(String name, Long excludeId) {
		QueryWrapper q = QueryWrapper.create().eq("model_name", name);
		if (excludeId != null) {
			q.ne("id", excludeId);
		}
		if (modelMapper.selectCountByQuery(q) > 0) {
			throw new ServiceException("已经有叫「" + name + "」的模型。请换一个名称，或打开原来那个");
		}
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
