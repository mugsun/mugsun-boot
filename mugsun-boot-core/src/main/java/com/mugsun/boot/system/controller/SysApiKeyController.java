package com.mugsun.boot.system.controller;

import cn.dev33.satoken.annotation.SaCheckLogin;
import com.mugsun.boot.system.entity.SysApiKey;
import com.mugsun.boot.system.mapper.SysApiKeyMapper;
import com.mugsun.boot.system.service.ApiKeyCallRecorder;
import com.mugsun.boot.tenant.TenantContext;
import com.mugsun.core.tool.api.R;
import com.mugsun.core.tool.exception.ServiceException;
import com.mybatisflex.core.paginate.Page;
import com.mybatisflex.core.query.QueryWrapper;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.*;

import java.security.SecureRandom;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * API 密钥管理：生成 AK/SK、启停、删除、校验。
 */
@RestController
@RequestMapping("/system/api-key")
@SaCheckLogin
public class SysApiKeyController {

	private static final String CHARS = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789";
	private static final SecureRandom RANDOM = new SecureRandom();

	private final SysApiKeyMapper apiKeyMapper;
	private final ApiKeyCallRecorder callRecorder;

	public SysApiKeyController(SysApiKeyMapper apiKeyMapper, ApiKeyCallRecorder callRecorder) {
		this.apiKeyMapper = apiKeyMapper;
		this.callRecorder = callRecorder;
	}

	@GetMapping("/page")
	public R<Page<SysApiKey>> page(@RequestParam(defaultValue = "1") long pageNum,
								   @RequestParam(defaultValue = "10") long pageSize) {
		Page<SysApiKey> page = apiKeyMapper.paginate(pageNum, pageSize, QueryWrapper.create().orderBy("id", false));
		// 列表脱敏：SK 仅显示前 6 位
		page.getRecords().forEach(k -> {
			String sk = k.getSecretKey();
			if (sk != null && sk.length() > 6) {
				k.setSecretKey(sk.substring(0, 6) + "******");
			}
		});
		return R.data(page);
	}

	/** 生成密钥：随机 AK/SK，返回含明文 SK 的记录（仅此一次） */
	@PostMapping("/generate")
	public R<SysApiKey> generate(@RequestBody SysApiKey param) {
		SysApiKey key = new SysApiKey();
		key.setTenantId(TenantContext.current());
		key.setName(param.getName());
		key.setScope(param.getScope());
		key.setRemark(param.getRemark());
		key.setStatus(1);
		key.setAccessKey("AK" + randomStr(16));
		key.setSecretKey(randomStr(32));
		apiKeyMapper.insertSelective(key);
		return R.data(key);
	}

	@PostMapping("/enable/{id}")
	public R<Void> enable(@PathVariable Long id) {
		SysApiKey key = apiKeyMapper.selectOneById(id);
		if (key != null) {
			key.setStatus(1);
			apiKeyMapper.update(key);
		}
		return R.success("已启用");
	}

	@PostMapping("/disable/{id}")
	public R<Void> disable(@PathVariable Long id) {
		SysApiKey key = apiKeyMapper.selectOneById(id);
		if (key != null) {
			key.setStatus(0);
			apiKeyMapper.update(key);
		}
		return R.success("已停用");
	}

	@PostMapping("/remove/{id}")
	public R<Void> remove(@PathVariable Long id) {
		apiKeyMapper.deleteById(id);
		return R.success("删除成功");
	}

	/** 校验密钥：AK+SK 匹配且启用则通过，并留下一条不含密钥明文的调用记录 */
	@PostMapping("/verify")
	public R<Map<String, Object>> verify(@RequestBody(required = false) VerifyParam param, HttpServletRequest request) {
		long start = System.nanoTime();
		String accessKey = param == null || param.accessKey() == null ? "" : param.accessKey().trim();
		String secretKey = param == null || param.secretKey() == null ? "" : param.secretKey();
		if (accessKey.isEmpty()) {
			throw new ServiceException("请填写 AccessKey");
		}
		if (secretKey.isBlank()) {
			throw new ServiceException("请填写 SecretKey。请使用生成密钥时保存的值，不要使用列表里打码后的内容");
		}
		SysApiKey key;
		if (TenantContext.isPlatformSuperAdmin()) {
			key = TenantContext.ignore(() -> apiKeyMapper.selectOneByQuery(
				QueryWrapper.create().eq("access_key", accessKey)));
		} else {
			QueryWrapper query = QueryWrapper.create().eq("access_key", accessKey);
			String tenant = TenantContext.current();
			if (tenant != null && !tenant.isBlank()) {
				query.eq("tenant_id", tenant);
			}
			key = apiKeyMapper.selectOneByQuery(query);
		}
		boolean enabled = key != null && Integer.valueOf(1).equals(key.getStatus());
		boolean valid = enabled && secretKey.equals(key.getSecretKey());
		String message;
		if (key == null) {
			message = "没有找到这个 AccessKey。请到密钥管理核对标识后再试";
		} else if (!enabled) {
			message = "这个密钥已停用。请让管理员启用后再调用";
		} else if (!valid) {
			message = "SecretKey 不正确。请使用生成时保存的密钥，不要使用列表里打码后的值";
		} else {
			message = "校验通过";
		}
		int costMs = (int) ((System.nanoTime() - start) / 1_000_000L);
		callRecorder.record(key, accessKey, true, request, valid, message, costMs);
		Map<String, Object> result = new LinkedHashMap<>();
		result.put("valid", valid);
		result.put("scope", valid && key.getScope() != null ? key.getScope() : "");
		result.put("message", message);
		return R.data(result);
	}

	private String randomStr(int len) {
		StringBuilder sb = new StringBuilder(len);
		for (int i = 0; i < len; i++) {
			sb.append(CHARS.charAt(RANDOM.nextInt(CHARS.length())));
		}
		return sb.toString();
	}

	/** 校验参数 */
	public record VerifyParam(String accessKey, String secretKey) {
	}
}
