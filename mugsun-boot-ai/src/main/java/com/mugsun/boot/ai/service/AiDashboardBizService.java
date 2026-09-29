package com.mugsun.boot.ai.service;

import com.mugsun.boot.ai.AiConstants;
import com.mugsun.boot.ai.AiModuleService;
import com.mugsun.boot.ai.entity.AiDashboard;
import com.mugsun.boot.ai.mapper.AiDashboardMapper;
import com.mugsun.boot.tenant.TenantContext;
import com.mugsun.core.tool.exception.ServiceException;
import com.mybatisflex.core.paginate.Page;
import com.mybatisflex.core.query.QueryWrapper;
import org.springframework.stereotype.Service;

@Service
public class AiDashboardBizService {

	private final AiModuleService moduleService;
	private final AiDashboardMapper mapper;

	public AiDashboardBizService(AiModuleService moduleService, AiDashboardMapper mapper) {
		this.moduleService = moduleService;
		this.mapper = mapper;
	}

	public Page<AiDashboard> page(long pageNum, long pageSize) {
		moduleService.requireEnabled();
		return mapper.paginate(pageNum, pageSize, QueryWrapper.create().orderBy("id", false));
	}

	public AiDashboard detail(Long id) {
		moduleService.requireEnabled();
		return require(id);
	}

	public AiDashboard submit(AiDashboard body) {
		moduleService.requireEnabled();
		if (body.getName() == null || body.getName().isBlank()) {
			throw new ServiceException("请填写名称");
		}
		if (body.getId() == null) {
			body.sanitizeForInsert();
			body.setTenantId(TenantContext.current());
			if (body.getEnabled() == null) {
				body.setEnabled(AiConstants.FLAG_YES);
			}
			mapper.insert(body);
		} else {
			body.sanitizeForUpdate();
			mapper.update(body);
		}
		return body;
	}

	public AiDashboard saveDsl(Long id, String dsl) {
		moduleService.requireEnabled();
		AiDashboard d = require(id);
		d.setDsl(dsl);
		d.sanitizeForUpdate();
		mapper.update(d);
		return d;
	}

	private AiDashboard require(Long id) {
		AiDashboard d = mapper.selectOneById(id);
		if (d == null) {
			throw new ServiceException(AiConstants.MSG_DASHBOARD_MISSING);
		}
		return d;
	}
}
