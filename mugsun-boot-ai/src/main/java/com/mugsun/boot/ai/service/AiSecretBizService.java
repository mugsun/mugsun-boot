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

	public Page<AiSecret> page(long pageNum, long pageSize) {
		moduleService.requireEnabled();
		Page<AiSecret> page = mapper.paginate(pageNum, pageSize, QueryWrapper.create().orderBy("id", false));
		page.getRecords().forEach(this::mask);
		return page;
	}

	public Map<String, Object> create(AiSecret body) {
		moduleService.requireEnabled();
		if (body.getDescription() == null || body.getDescription().isBlank()) {
			throw new ServiceException("请填写密钥说明");
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
		resp.put("secretPrefix", body.getSecretPrefix());
		resp.put("message", AiConstants.MSG_SECRET_ONCE);
		return resp;
	}

	public void remove(List<Long> ids) {
		moduleService.requireEnabled();
		for (Long id : ids) {
			require(id);
			mapper.deleteById(id);
		}
	}

	public void status(Long id, Integer status) {
		moduleService.requireEnabled();
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

	private AiSecret require(Long id) {
		AiSecret s = mapper.selectOneById(id);
		if (s == null) {
			throw new ServiceException(AiConstants.MSG_SECRET_MISSING);
		}
		return s;
	}

	private void mask(AiSecret s) {
		if (s != null) {
			s.setSecretKey(null);
		}
	}
}
