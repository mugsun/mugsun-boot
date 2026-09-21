package com.mugsun.boot.system.controller;

import cn.dev33.satoken.annotation.SaCheckLogin;
import com.mugsun.boot.system.entity.SysApiKey;
import com.mugsun.boot.system.mapper.SysApiKeyMapper;
import com.mugsun.core.tool.api.R;
import com.mybatisflex.core.paginate.Page;
import com.mybatisflex.core.query.QueryWrapper;
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

	public SysApiKeyController(SysApiKeyMapper apiKeyMapper) {
		this.apiKeyMapper = apiKeyMapper;
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

	/** 校验密钥：AK+SK 匹配且启用则通过，返回作用域 */
	@PostMapping("/verify")
	public R<Map<String, Object>> verify(@RequestBody VerifyParam param) {
		SysApiKey key = apiKeyMapper.selectOneByQuery(QueryWrapper.create().eq("access_key", param.accessKey()));
		boolean valid = key != null && Integer.valueOf(1).equals(key.getStatus())
			&& key.getSecretKey().equals(param.secretKey());
		Map<String, Object> result = new LinkedHashMap<>();
		result.put("valid", valid);
		result.put("scope", valid && key.getScope() != null ? key.getScope() : "");
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
