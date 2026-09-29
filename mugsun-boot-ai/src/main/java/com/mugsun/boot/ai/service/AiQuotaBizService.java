package com.mugsun.boot.ai.service;

import com.mugsun.boot.ai.AiConstants;
import com.mugsun.boot.ai.AiModuleService;
import com.mugsun.boot.ai.entity.AiQuota;
import com.mugsun.boot.ai.mapper.AiQuotaMapper;
import com.mugsun.boot.tenant.TenantContext;
import com.mugsun.core.tool.exception.ServiceException;
import com.mybatisflex.core.paginate.Page;
import com.mybatisflex.core.query.QueryWrapper;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

@Service
public class AiQuotaBizService {

	private final AiModuleService moduleService;
	private final AiQuotaMapper mapper;

	public AiQuotaBizService(AiModuleService moduleService, AiQuotaMapper mapper) {
		this.moduleService = moduleService;
		this.mapper = mapper;
	}

	public Page<AiQuota> page(long pageNum, long pageSize) {
		moduleService.requireEnabled();
		return mapper.paginate(pageNum, pageSize, QueryWrapper.create().orderBy("id", false));
	}

	public AiQuota submit(AiQuota body) {
		moduleService.requireEnabled();
		if (body.getId() == null) {
			body.sanitizeForInsert();
			body.setTenantId(TenantContext.current());
			if (body.getStatus() == null) {
				body.setStatus(AiConstants.STATUS_ENABLE);
			}
			mapper.insert(body);
		} else {
			body.sanitizeForUpdate();
			mapper.update(body);
		}
		return body;
	}

	public List<Map<String, Object>> trend() {
		moduleService.requireEnabled();
		List<AiQuota> list = mapper.selectListByQuery(QueryWrapper.create().orderBy("id", false).limit(12));
		return list.stream().map(q -> Map.<String, Object>of(
			"period", q.getPeriod() == null ? "" : q.getPeriod(),
			"usedTokens", q.getUsedTokens() == null ? 0 : q.getUsedTokens(),
			"tokenLimit", q.getTokenLimit() == null ? 0 : q.getTokenLimit()
		)).toList();
	}

	/**
	 * OpenAPI / 对话前配额闸门：超限且 overAction=reject 时拒绝；warn 仅放行。
	 */
	public void assertWithinQuota(String tenantId, long estimateTokens) {
		moduleService.requireEnabled();
		String tid = tenantId == null || tenantId.isBlank() ? TenantContext.current() : tenantId;
		List<AiQuota> list = mapper.selectListByQuery(
			QueryWrapper.create().eq("tenant_id", tid).eq("status", AiConstants.STATUS_ENABLE).orderBy("id", false).limit(1));
		if (list.isEmpty()) {
			return;
		}
		AiQuota q = list.get(0);
		long used = q.getUsedTokens() == null ? 0 : q.getUsedTokens();
		long limit = q.getTokenLimit() == null ? 0 : q.getTokenLimit();
		if (limit > 0 && used + estimateTokens > limit) {
			String action = q.getOverAction() == null ? "reject" : q.getOverAction();
			if ("reject".equalsIgnoreCase(action) || "block".equalsIgnoreCase(action)) {
				throw new ServiceException(AiConstants.MSG_QUOTA_EXCEEDED);
			}
		}
	}

	public void consume(String tenantId, long tokens) {
		if (tokens <= 0) {
			return;
		}
		String tid = tenantId == null || tenantId.isBlank() ? TenantContext.current() : tenantId;
		List<AiQuota> list = mapper.selectListByQuery(
			QueryWrapper.create().eq("tenant_id", tid).eq("status", AiConstants.STATUS_ENABLE).orderBy("id", false).limit(1));
		if (list.isEmpty()) {
			return;
		}
		AiQuota q = list.get(0);
		q.setUsedTokens((q.getUsedTokens() == null ? 0 : q.getUsedTokens()) + tokens);
		q.sanitizeForUpdate();
		mapper.update(q);
	}
}
