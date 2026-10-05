package com.mugsun.boot.system.controller;

import cn.dev33.satoken.annotation.SaCheckLogin;
import cn.dev33.satoken.annotation.SaCheckPermission;
import com.mugsun.boot.system.entity.SysApiKey;
import com.mugsun.boot.system.entity.SysApiKeyLog;
import com.mugsun.boot.system.mapper.SysApiKeyLogMapper;
import com.mugsun.boot.system.mapper.SysApiKeyMapper;
import com.mugsun.boot.tenant.TenantContext;
import com.mugsun.core.tool.api.R;
import com.mugsun.core.tool.exception.ServiceException;
import com.mybatisflex.core.paginate.Page;
import com.mybatisflex.core.query.QueryWrapper;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 某把密钥的调用记录。平台管理员可看全部租户，其余只能看本租户。
 */
@RestController
@RequestMapping("/system/api-key-log")
@SaCheckLogin
public class SysApiKeyLogController {

	private final SysApiKeyLogMapper logMapper;
	private final SysApiKeyMapper apiKeyMapper;

	public SysApiKeyLogController(SysApiKeyLogMapper logMapper, SysApiKeyMapper apiKeyMapper) {
		this.logMapper = logMapper;
		this.apiKeyMapper = apiKeyMapper;
	}

	@GetMapping("/page")
	@SaCheckPermission("sys:api-key:log")
	public R<Page<SysApiKeyLog>> page(@RequestParam(required = false) Long apiKeyId,
									  @RequestParam(defaultValue = "1") long pageNum,
									  @RequestParam(defaultValue = "10") long pageSize,
									  @RequestParam(required = false) Integer status,
									  @RequestParam(required = false) String ip,
									  @RequestParam(required = false) String uri) {
		if (apiKeyId == null) {
			throw new ServiceException("请先在密钥列表里选择一把密钥，再查看它的调用记录");
		}
		assertKeyVisible(apiKeyId);
		if (status != null && status != 0 && status != 1) {
			throw new ServiceException("结果请选择通过或拒绝");
		}
		if (ip != null && ip.trim().length() > 64) {
			throw new ServiceException("IP 请控制在 64 字以内");
		}
		QueryWrapper query = QueryWrapper.create().eq("api_key_id", apiKeyId).orderBy("id", false);
		if (status != null) {
			query.eq("status", status);
		}
		if (ip != null && !ip.isBlank()) {
			query.like("remote_ip", ip.trim());
		}
		if (uri != null && !uri.isBlank()) {
			query.like("request_uri", uri.trim());
		}
		Page<SysApiKeyLog> page = TenantContext.isPlatformSuperAdmin()
			? TenantContext.ignore(() -> logMapper.paginate(pageNum, Math.min(pageSize, 100), query))
			: logMapper.paginate(pageNum, Math.min(pageSize, 100), query);
		return R.data(page);
	}

	@GetMapping("/detail")
	@SaCheckPermission("sys:api-key:log")
	public R<SysApiKeyLog> detail(@RequestParam(required = false) Long id) {
		if (id == null) {
			throw new ServiceException("请从调用记录里选择一条后再查看");
		}
		SysApiKeyLog row = TenantContext.isPlatformSuperAdmin()
			? TenantContext.ignore(() -> logMapper.selectOneById(id))
			: logMapper.selectOneById(id);
		if (row == null) {
			throw new ServiceException("这条调用记录不存在，或属于其他租户。请刷新列表后再看");
		}
		return R.data(row);
	}

	private void assertKeyVisible(Long apiKeyId) {
		SysApiKey key = TenantContext.isPlatformSuperAdmin()
			? TenantContext.ignore(() -> apiKeyMapper.selectOneById(apiKeyId))
			: apiKeyMapper.selectOneById(apiKeyId);
		if (key == null) {
			throw new ServiceException("这把密钥不存在，或属于其他租户。请刷新密钥列表后再看");
		}
	}
}
