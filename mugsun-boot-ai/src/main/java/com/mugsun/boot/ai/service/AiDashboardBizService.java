package com.mugsun.boot.ai.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mugsun.boot.ai.AiConstants;
import com.mugsun.boot.ai.AiModuleService;
import com.mugsun.boot.ai.entity.AiDashboard;
import com.mugsun.boot.ai.mapper.AiDashboardMapper;
import com.mugsun.boot.ai.mapper.AiDatasetMapper;
import com.mugsun.boot.tenant.TenantContext;
import com.mugsun.core.tool.exception.ServiceException;
import com.mybatisflex.core.paginate.Page;
import com.mybatisflex.core.query.QueryWrapper;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class AiDashboardBizService {

	private static final int DSL_LIMIT = 20000;

	private final AiModuleService moduleService;
	private final AiDashboardMapper mapper;
	private final AiDatasetMapper datasetMapper;
	private final ObjectMapper objectMapper;

	public AiDashboardBizService(AiModuleService moduleService, AiDashboardMapper mapper,
								 AiDatasetMapper datasetMapper, ObjectMapper objectMapper) {
		this.moduleService = moduleService;
		this.mapper = mapper;
		this.datasetMapper = datasetMapper;
		this.objectMapper = objectMapper;
	}

	public Page<AiDashboard> page(long pageNum, long pageSize, String name) {
		moduleService.requireEnabled();
		if (pageNum < 1) {
			throw new ServiceException("页码请从 1 开始");
		}
		if (pageSize < 1 || pageSize > 100) {
			throw new ServiceException("每页条数请在 1 到 100 之间");
		}
		QueryWrapper query = QueryWrapper.create().orderBy("id", false);
		if (name != null && !name.isBlank()) {
			String keyword = name.trim();
			if (keyword.length() > 64) {
				keyword = keyword.substring(0, 64);
			}
			query.like("name", keyword);
		}
		return mapper.paginate(pageNum, pageSize, query);
	}

	/** 按仪表盘编号打开；对不上时再按问数编号找最近一份。都没有则返回 null，方便第一次进入后新建。 */
	public AiDashboard open(Long id) {
		moduleService.requireEnabled();
		if (id == null) {
			return null;
		}
		AiDashboard byId = mapper.selectOneById(id);
		if (byId != null) {
			return byId;
		}
		return mapper.selectOneByQuery(QueryWrapper.create().eq("dataset_id", id).orderBy("id", false));
	}

	public AiDashboard submit(AiDashboard body) {
		moduleService.requireEnabled();
		if (body == null) {
			throw new ServiceException("请填写仪表盘名称。用来在列表里区分");
		}
		String name = normalizeName(body.getName());
		String description = body.getDescription() == null ? "" : body.getDescription().trim();
		if (description.length() > 200) {
			throw new ServiceException("描述请控制在 200 个字以内");
		}
		if (body.getEnabled() != null && body.getEnabled() != 0 && body.getEnabled() != 1) {
			throw new ServiceException("状态请选择启用或停用");
		}
		if (body.getDatasetId() != null && datasetMapper.selectOneById(body.getDatasetId()) == null) {
			throw new ServiceException("这个问数不存在。请从问数列表再进一次仪表盘");
		}
		String dsl = body.getDsl();
		if (dsl != null && !dsl.isBlank()) {
			dsl = requireDsl(dsl);
		} else {
			dsl = null;
		}
		requireUnique(name, body.getId());
		if (body.getId() == null) {
			body.sanitizeForInsert();
			body.setTenantId(TenantContext.current());
			body.setName(name);
			body.setDescription(description.isEmpty() ? null : description);
			body.setDsl(dsl);
			if (body.getEnabled() == null) {
				body.setEnabled(AiConstants.FLAG_YES);
			}
			mapper.insert(body);
			return body;
		}
		AiDashboard db = require(body.getId());
		db.setName(name);
		db.setDescription(description.isEmpty() ? null : description);
		if (dsl != null) {
			db.setDsl(dsl);
		}
		if (body.getEnabled() != null) {
			db.setEnabled(body.getEnabled());
		}
		if (body.getDatasetId() != null) {
			db.setDatasetId(body.getDatasetId());
		}
		mapper.update(db);
		return db;
	}

	public List<AiDashboard> remove(List<Long> ids) {
		moduleService.requireEnabled();
		if (ids == null || ids.isEmpty()) {
			throw new ServiceException("请先选择要删除的仪表盘");
		}
		List<AiDashboard> rows = new ArrayList<>();
		for (Long id : ids) {
			rows.add(require(id));
		}
		for (AiDashboard row : rows) {
			mapper.deleteById(row.getId());
		}
		return rows;
	}

	public AiDashboard saveDsl(Long id, String name, String dsl) {
		moduleService.requireEnabled();
		AiDashboard dashboard = require(id);
		if (name != null && !name.isBlank()) {
			String next = normalizeName(name);
			requireUnique(next, id);
			dashboard.setName(next);
		}
		dashboard.setDsl(requireDsl(dsl));
		mapper.update(dashboard);
		return dashboard;
	}

	public Map<String, Object> share(Long id) {
		moduleService.requireEnabled();
		AiDashboard dashboard = require(id);
		Map<String, Object> data = new LinkedHashMap<>();
		data.put("url", "/#/ai/dashboard/design/" + dashboard.getId());
		data.put("message", "这是站内地址。对方需要登录，并且有仪表盘权限才能打开");
		return data;
	}

	private String normalizeName(String raw) {
		String name = raw == null ? "" : raw.trim();
		if (name.isEmpty()) {
			throw new ServiceException("请填写仪表盘名称。用来在列表里区分");
		}
		if (name.length() > 64) {
			throw new ServiceException("仪表盘名称请控制在 64 个字以内");
		}
		return name;
	}

	private String requireDsl(String dsl) {
		if (dsl == null || dsl.isBlank()) {
			throw new ServiceException("请填写 DSL。可以先写成 {\"charts\":[]}");
		}
		if (dsl.length() > DSL_LIMIT) {
			throw new ServiceException("DSL 请控制在 20000 个字符以内");
		}
		try {
			JsonNode node = objectMapper.readTree(dsl);
			if (!node.isObject() && !node.isArray()) {
				throw new ServiceException("DSL 需要是 JSON 对象或数组。可以先写成 {\"charts\":[]}");
			}
		} catch (ServiceException e) {
			throw e;
		} catch (Exception ignored) {
			throw new ServiceException("DSL 不是合法 JSON。可以先写成 {\"charts\":[]} 再改");
		}
		return dsl;
	}

	private void requireUnique(String name, Long id) {
		QueryWrapper query = QueryWrapper.create().eq("name", name);
		if (id != null) {
			query.ne("id", id);
		}
		if (mapper.selectCountByQuery(query) > 0) {
			throw new ServiceException("已经有叫「" + name + "」的仪表盘。请换一个名称，或打开原来那个");
		}
	}

	private AiDashboard require(Long id) {
		AiDashboard dashboard = id == null ? null : mapper.selectOneById(id);
		if (dashboard == null) {
			throw new ServiceException(AiConstants.MSG_DASHBOARD_MISSING);
		}
		return dashboard;
	}
}
