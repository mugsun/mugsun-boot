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
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Service
public class AiVectorStoreBizService {

	private static final Set<String> TYPES = Set.of("pgvector", "milvus", "custom");
	private static final String FAIL = "这次没有连上。请检查地址、用户名和密码后再试";

	private final AiModuleService moduleService;
	private final AiVectorStoreMapper mapper;

	public AiVectorStoreBizService(AiModuleService moduleService, AiVectorStoreMapper mapper) {
		this.moduleService = moduleService;
		this.mapper = mapper;
	}

	public Page<AiVectorStore> page(long pageNum, long pageSize, String name) {
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
		Page<AiVectorStore> page = mapper.paginate(pageNum, pageSize, query);
		page.getRecords().forEach(this::mask);
		return page;
	}

	public AiVectorStore detail(Long id) {
		moduleService.requireEnabled();
		AiVectorStore row = require(id);
		mask(row);
		return row;
	}

	public AiVectorStore submit(AiVectorStore body) {
		moduleService.requireEnabled();
		if (body == null) {
			throw new ServiceException("请填写向量库名称。用来在列表里区分");
		}
		String name = body.getName() == null ? "" : body.getName().trim();
		if (name.isEmpty()) {
			throw new ServiceException("请填写向量库名称。用来在列表里区分");
		}
		if (name.length() > 64) {
			throw new ServiceException("向量库名称请控制在 64 个字以内");
		}
		String type = body.getStoreType() == null ? "" : body.getStoreType().trim().toLowerCase();
		if (!TYPES.contains(type)) {
			throw new ServiceException("类型请选择 PgVector、Milvus 或自定义");
		}
		body.setStoreType(type);
		String rawUrl = body.getSubmittedUrl() == null ? "" : body.getSubmittedUrl().trim();
		if (rawUrl.isEmpty()) {
			throw new ServiceException("请填写连接地址。PgVector 用 jdbc:postgresql://主机:端口/库名");
		}
		body.setUrl(rawUrl);
		if ("pgvector".equals(type) && !rawUrl.startsWith("jdbc:postgresql://")) {
			throw new ServiceException("PgVector 的地址需要以 jdbc:postgresql:// 开头");
		}
		String host = body.getHost() == null ? "" : body.getHost().trim();
		if (host.isEmpty() || host.length() > 128 || host.contains(" ") || host.contains("<")) {
			throw new ServiceException("连接地址请控制在 128 个字符以内，并且不要带空格");
		}
		if (body.getPort() == null || body.getPort() < 1 || body.getPort() > 65535) {
			throw new ServiceException("请在地址里写上端口，范围是 1 到 65535");
		}
		String database = body.getDatabaseName() == null ? "" : body.getDatabaseName().trim();
		if (database.length() > 64) {
			throw new ServiceException("库名请控制在 64 个字以内");
		}
		String username = body.getUsername() == null ? "" : body.getUsername().trim();
		if (username.length() > 64) {
			throw new ServiceException("用户名请控制在 64 个字以内");
		}
		String password = body.getPassword() == null ? "" : body.getPassword();
		if (password.length() > 128) {
			throw new ServiceException("密码请控制在 128 个字以内");
		}
		if (body.getDimensions() != null && (body.getDimensions() < 1 || body.getDimensions() > 8192)) {
			throw new ServiceException("维度请填 1 到 8192");
		}
		String remark = body.getRemark() == null ? "" : body.getRemark().trim();
		if (remark.length() > 255) {
			throw new ServiceException("备注请控制在 255 个字以内");
		}
		requireUnique(name, body.getId());
		if (body.getId() == null) {
			body.sanitizeForInsert();
			body.setTenantId(TenantContext.current());
			body.setName(name);
			body.setHost(host);
			body.setDatabaseName(database.isEmpty() ? null : database);
			body.setUsername(username.isEmpty() ? null : username);
			body.setPassword(password.isEmpty() ? null : password);
			body.setRemark(remark.isEmpty() ? null : remark);
			body.setBuiltinFlag(AiConstants.FLAG_NO);
			mapper.insert(body);
			mask(body);
			return body;
		}
		AiVectorStore db = require(body.getId());
		db.setName(name);
		db.setStoreType(type);
		db.setHost(host);
		db.setPort(body.getPort());
		db.setDatabaseName(database.isEmpty() ? null : database);
		db.setUsername(username.isEmpty() ? null : username);
		db.setDimensions(body.getDimensions());
		db.setRemark(remark.isEmpty() ? null : remark);
		if (!password.isEmpty()) {
			db.setPassword(password);
		}
		mapper.update(db);
		mask(db);
		return db;
	}

	public List<AiVectorStore> remove(List<Long> ids) {
		moduleService.requireEnabled();
		if (ids == null || ids.isEmpty()) {
			throw new ServiceException("请先选择要删除的向量库");
		}
		List<AiVectorStore> rows = new ArrayList<>();
		for (Long id : ids) {
			AiVectorStore row = require(id);
			if (Integer.valueOf(AiConstants.FLAG_YES).equals(row.getBuiltinFlag())) {
				throw new ServiceException("内置向量库不能删除。可以改连接，或另外添加一个向量库");
			}
			rows.add(row);
		}
		for (AiVectorStore row : rows) {
			mapper.deleteById(row.getId());
		}
		return rows;
	}

	public Map<String, Object> test(Long id) {
		moduleService.requireEnabled();
		AiVectorStore row = require(id);
		if (!"pgvector".equalsIgnoreCase(row.getStoreType())) {
			return result(false, "这个类型还不能自动测试。请核对地址后再用");
		}
		String host = row.getHost() == null ? "" : row.getHost().trim();
		if (host.isEmpty() || row.getPort() == null) {
			return result(false, "还没有完整的连接地址。请先编辑，填上主机和端口");
		}
		String url = "jdbc:postgresql://" + host + ":" + row.getPort() + "/"
			+ (row.getDatabaseName() == null ? "" : row.getDatabaseName());
		try (Connection connection = DriverManager.getConnection(url,
			row.getUsername() == null ? "" : row.getUsername(),
			row.getPassword() == null ? "" : row.getPassword())) {
			if (!connection.isValid(3)) {
				return result(false, FAIL);
			}
			return result(true, "已连上。知识库检索可以用这个向量库");
		} catch (Exception ignored) {
			return result(false, FAIL);
		}
	}

	private void requireUnique(String name, Long id) {
		QueryWrapper query = QueryWrapper.create().eq("name", name);
		if (id != null) {
			query.ne("id", id);
		}
		if (mapper.selectCountByQuery(query) > 0) {
			throw new ServiceException("已经有叫「" + name + "」的向量库。请换一个名称，或打开原来那个");
		}
	}

	private AiVectorStore require(Long id) {
		AiVectorStore row = mapper.selectOneById(id);
		if (row == null) {
			throw new ServiceException(AiConstants.MSG_VECTOR_MISSING);
		}
		return row;
	}

	private void mask(AiVectorStore row) {
		if (row != null) {
			row.setPassword(null);
		}
	}

	private static Map<String, Object> result(boolean ok, String message) {
		Map<String, Object> data = new LinkedHashMap<>();
		data.put("ok", ok);
		data.put("message", message);
		return data;
	}
}
