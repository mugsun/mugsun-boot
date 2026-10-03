package com.mugsun.boot.ai.service;

import com.mugsun.boot.ai.AiConstants;
import com.mugsun.boot.ai.AiModuleService;
import com.mugsun.boot.ai.entity.AiVectorStore;
import com.mugsun.boot.ai.mapper.AiVectorStoreMapper;
import com.mugsun.boot.tenant.TenantContext;
import com.mugsun.core.tool.exception.ServiceException;
import com.mybatisflex.core.paginate.Page;
import com.mybatisflex.core.query.QueryWrapper;
import org.springframework.stereotype.Service;

import java.sql.Connection;
import java.sql.DriverManager;
import java.util.List;
import java.util.Map;

@Service
public class AiVectorStoreBizService {

	private final AiModuleService moduleService;
	private final AiVectorStoreMapper mapper;

	public AiVectorStoreBizService(AiModuleService moduleService, AiVectorStoreMapper mapper) {
		this.moduleService = moduleService;
		this.mapper = mapper;
	}

	public Page<AiVectorStore> page(long pageNum, long pageSize, String name) {
		moduleService.requireEnabled();
		QueryWrapper q = QueryWrapper.create().orderBy("id", false);
		if (name != null && !name.isBlank()) {
			q.like("name", name.trim());
		}
		Page<AiVectorStore> page = mapper.paginate(pageNum, pageSize, q);
		page.getRecords().forEach(this::mask);
		return page;
	}

	public AiVectorStore detail(Long id) {
		moduleService.requireEnabled();
		AiVectorStore v = require(id);
		mask(v);
		return v;
	}

	public AiVectorStore submit(AiVectorStore body) {
		moduleService.requireEnabled();
		if (body.getName() == null || body.getName().isBlank()) {
			throw new ServiceException("请填写名称");
		}
		if (body.getId() == null) {
			body.sanitizeForInsert();
			body.setTenantId(TenantContext.current());
			if (body.getBuiltinFlag() == null) {
				body.setBuiltinFlag(AiConstants.FLAG_NO);
			}
			mapper.insert(body);
		} else {
			AiVectorStore db = require(body.getId());
			String keepPwd = db.getPassword();
			body.sanitizeForUpdate();
			if (body.getPassword() == null || body.getPassword().isBlank()) {
				body.setPassword(keepPwd);
			}
			body.setBuiltinFlag(db.getBuiltinFlag());
			body.setTenantId(db.getTenantId());
			mapper.update(body);
		}
		mask(body);
		return body;
	}

	public void remove(List<Long> ids) {
		moduleService.requireEnabled();
		for (Long id : ids) {
			AiVectorStore v = require(id);
			if (Integer.valueOf(AiConstants.FLAG_YES).equals(v.getBuiltinFlag())) {
				throw new ServiceException(AiConstants.MSG_BUILTIN_LOCKED);
			}
			mapper.deleteById(id);
		}
	}

	public Map<String, Object> test(Long id) {
		moduleService.requireEnabled();
		AiVectorStore v = require(id);
		if ("pgvector".equalsIgnoreCase(v.getStoreType())) {
			String host = v.getHost() == null ? "localhost" : v.getHost();
			int port = v.getPort() == null ? 5432 : v.getPort();
			String db = v.getDatabaseName() == null ? "mugsun" : v.getDatabaseName();
			String url = "jdbc:postgresql://" + host + ":" + port + "/" + db;
			try (Connection c = DriverManager.getConnection(url,
				v.getUsername() == null ? "mugsun" : v.getUsername(),
				v.getPassword() == null ? "" : v.getPassword())) {
				boolean ok = c.isValid(5);
				return Map.of("ok", ok);
			} catch (Exception e) {
				// 内置指向本库时可认为配置合法，连接失败仍返回信息
				return Map.of("ok", false, "message", e.getMessage() == null ? "连接失败" : e.getMessage());
			}
		}
		return Map.of("ok", false, "message", "当前类型不能自动探测连接，请核对地址");
	}

	private AiVectorStore require(Long id) {
		AiVectorStore v = mapper.selectOneById(id);
		if (v == null) {
			throw new ServiceException(AiConstants.MSG_VECTOR_MISSING);
		}
		return v;
	}

	private void mask(AiVectorStore v) {
		if (v != null) {
			v.setPassword(null);
		}
	}
}
