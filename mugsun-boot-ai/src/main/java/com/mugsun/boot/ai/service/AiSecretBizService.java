package com.mugsun.boot.ai.service;

import cn.hutool.core.util.IdUtil;
import com.mugsun.boot.ai.AiConstants;
import com.mugsun.boot.ai.AiModuleService;
import com.mugsun.boot.ai.entity.AiSecret;
import com.mugsun.boot.ai.mapper.AiSecretMapper;
import com.mugsun.boot.tenant.TenantContext;
import com.mugsun.core.tool.exception.ServiceException;
import com.mybatisflex.core.paginate.Page;
import com.mybatisflex.core.query.QueryWrapper;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Service
public class AiSecretBizService {

	private final AiModuleService moduleService;
	private final AiSecretMapper mapper;

	public AiSecretBizService(AiModuleService moduleService, AiSecretMapper mapper) {
		this.moduleService = moduleService;
		this.mapper = mapper;
	}

	private static final java.util.Set<String> SCOPES = java.util.Set.of("tenant", "user", "app");

	public Page<AiSecret> page(long pageNum, long pageSize) {
		moduleService.requireEnabled();
		if (pageNum < 1) {
			throw new ServiceException("页码请从 1 开始");
		}
		if (pageSize < 1 || pageSize > 100) {
			throw new ServiceException("每页条数请在 1 到 100 之间");
		}
		Page<AiSecret> page = mapper.paginate(pageNum, pageSize, QueryWrapper.create().orderBy("id", false));
		page.getRecords().forEach(this::mask);
		return page;
	}

	public Map<String, Object> create(AiSecret body) {
		moduleService.requireEnabled();
		if (body == null) {
			throw new ServiceException("请填写密钥说明。用来在列表里区分");
		}
		String description = body.getDescription() == null ? "" : body.getDescription().trim();
		if (description.isEmpty()) {
			throw new ServiceException("请填写密钥说明。用来在列表里区分");
		}
		if (description.length() > 128) {
			throw new ServiceException("密钥说明请控制在 128 个字以内");
		}
		String scope = body.getScope() == null || body.getScope().isBlank() ? "tenant" : body.getScope().trim();
		if (!SCOPES.contains(scope)) {
			throw new ServiceException("作用域请选择租户、用户或应用");
		}
		if (body.getRateLimit() != null && (body.getRateLimit() < 0 || body.getRateLimit() > 100000)) {
			throw new ServiceException("限流请填 0 到 100000。0 表示不限制");
		}
		String allowIps = body.getAllowIps() == null ? null : body.getAllowIps().trim();
		if (allowIps != null && !allowIps.isEmpty() && !validIps(allowIps)) {
			throw new ServiceException("允许的地址请写成 IP 或网段，多个用英文逗号隔开");
		}
		if (body.getStatus() != null && body.getStatus() != 0 && body.getStatus() != 1) {
			throw new ServiceException("状态请选择启用或停用");
		}
		if (body.getExpireTime() != null && body.getExpireTime().isBefore(LocalDateTime.now())) {
			throw new ServiceException("过期时间已经过了。请改成以后的时间，或留空表示不过期");
		}
		body.setDescription(description);
		body.setScope(scope);
		body.setAllowIps(allowIps == null || allowIps.isEmpty() ? null : allowIps);
		requireUniqueDescription(description, body.getId());
		if (body.getId() != null) {
			AiSecret db = require(body.getId());
			db.setDescription(body.getDescription());
			if (body.getScope() != null) {
				db.setScope(body.getScope());
			}
			db.setScopeId(body.getScopeId());
			db.setRateLimit(body.getRateLimit());
			db.setAllowIps(body.getAllowIps());
			db.setExpireTime(body.getExpireTime());
			db.sanitizeForUpdate();
			mapper.update(db);
			Map<String, Object> resp = new HashMap<>();
			resp.put("id", db.getId());
			return resp;
		}
		String plain = AiConstants.SECRET_PREFIX + IdUtil.fastSimpleUUID();
		body.sanitizeForInsert();
		body.setTenantId(TenantContext.current());
		body.setSecretKey(plain);
		body.setSecretPrefix(plain.substring(0, Math.min(8, plain.length())));
		if (body.getStatus() == null) {
			body.setStatus(AiConstants.STATUS_ENABLE);
		}
		body.setUsedCount(0L);
		mapper.insert(body);
		Map<String, Object> resp = new HashMap<>();
		resp.put("id", body.getId());
		resp.put("secretKey", plain);
		resp.put("apiKey", plain);
		resp.put("secretPrefix", body.getSecretPrefix());
		resp.put("message", AiConstants.MSG_SECRET_ONCE);
		return resp;
	}

	public java.util.List<AiSecret> remove(List<Long> ids) {
		moduleService.requireEnabled();
		if (ids == null || ids.isEmpty()) {
			throw new ServiceException("请先选择要删除的密钥");
		}
		java.util.List<AiSecret> rows = new java.util.ArrayList<>();
		for (Long id : ids) {
			if (id == null) {
				throw new ServiceException("密钥编号请填写数字，可以从列表里复制");
			}
			rows.add(require(id));
		}
		for (AiSecret row : rows) {
			mapper.deleteById(row.getId());
		}
		return rows;
	}

	public void status(Long id, Integer status) {
		moduleService.requireEnabled();
		if (id == null) {
			throw new ServiceException("请先在列表里选择要改状态的密钥");
		}
		if (status == null || (status != 0 && status != 1)) {
			throw new ServiceException("状态请选择启用或停用");
		}
		AiSecret s = require(id);
		s.setStatus(status);
		s.sanitizeForUpdate();
		mapper.update(s);
	}

	/** OpenAPI 鉴权：按明文 Bearer sk- 匹配（SM4 解密后比较） */
	public AiSecret authenticate(String bearerToken) {
		moduleService.requireEnabled();
		if (bearerToken == null || bearerToken.isBlank()) {
			throw new ServiceException(AiConstants.MSG_OPENAPI_AUTH);
		}
		String token = bearerToken.startsWith("Bearer ") ? bearerToken.substring(7).trim() : bearerToken.trim();
		if (!token.startsWith(AiConstants.SECRET_PREFIX)) {
			throw new ServiceException(AiConstants.MSG_OPENAPI_AUTH);
		}
		String prefix = token.substring(0, Math.min(8, token.length()));
		List<AiSecret> candidates = mapper.selectListByQuery(
			QueryWrapper.create().eq("secret_prefix", prefix).eq("status", AiConstants.STATUS_ENABLE));
		for (AiSecret s : candidates) {
			if (token.equals(s.getSecretKey())) {
				if (s.getExpireTime() != null && s.getExpireTime().isBefore(LocalDateTime.now())) {
					throw new ServiceException(AiConstants.MSG_OPENAPI_AUTH);
				}
				s.setLastUsedTime(LocalDateTime.now());
				s.setUsedCount((s.getUsedCount() == null ? 0 : s.getUsedCount()) + 1);
				s.sanitizeForUpdate();
				mapper.update(s);
				return s;
			}
		}
		throw new ServiceException(AiConstants.MSG_OPENAPI_AUTH);
	}

	private void requireUniqueDescription(String description, Long excludeId) {
		QueryWrapper q = QueryWrapper.create().eq("description", description);
		if (excludeId != null) {
			q.ne("id", excludeId);
		}
		if (mapper.selectCountByQuery(q) > 0) {
			throw new ServiceException("已经有说明为「" + description + "」的密钥。请换一个说明，或打开原来那把");
		}
	}

	private static boolean validIps(String raw) {
		if (raw.length() > 512) {
			return false;
		}
		for (String part : raw.split(",")) {
			String token = part.trim();
			if (!token.matches("(\\d{1,3}\\.){3}\\d{1,3}(/\\d{1,2})?")) {
				return false;
			}
		}
		return true;
	}

	private AiSecret require(Long id) {
		AiSecret s = mapper.selectOneById(id);
		if (s == null) {
			throw new ServiceException(AiConstants.MSG_SECRET_MISSING);
		}
		return s;
	}

	private void mask(AiSecret s) {
		if (s != null) {
			String prefix = s.getSecretPrefix();
			s.setApiKeyMask(prefix == null || prefix.isBlank() ? "***" : prefix + "******");
			s.setSecretKey(null);
		}
	}
}
