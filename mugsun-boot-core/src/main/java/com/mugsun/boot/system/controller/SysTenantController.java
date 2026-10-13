package com.mugsun.boot.system.controller;

import cn.dev33.satoken.annotation.SaCheckLogin;
import com.mugsun.boot.common.repeat.RepeatSubmit;
import com.mugsun.boot.system.entity.SysTenant;
import com.mugsun.boot.system.mapper.SysTenantMapper;
import com.mugsun.boot.system.service.TenantService;
import com.mugsun.core.tool.api.R;
import com.mugsun.core.tool.exception.ServiceException;
import com.mybatisflex.core.query.QueryWrapper;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 租户管理（仅超管租户可操作）
 */
@RestController
@RequestMapping("/system/tenant")
@SaCheckLogin
public class SysTenantController {

	private final SysTenantMapper tenantMapper;
	private final TenantService tenantService;
	private final com.mugsun.boot.log.UsualLogService usualLogService;

	public SysTenantController(SysTenantMapper tenantMapper, TenantService tenantService,
							   com.mugsun.boot.log.UsualLogService usualLogService) {
		this.tenantMapper = tenantMapper;
		this.tenantService = tenantService;
		this.usualLogService = usualLogService;
	}

	/** 租户验证码开关：仅平台超管可改，写审计日志 */
	@RepeatSubmit
	@PostMapping("/captcha")
	public R<Void> captchaSwitch(@RequestBody CaptchaSwitchDTO dto) {
		if (!com.mugsun.boot.tenant.TenantContext.isPlatformSuperAdmin()) {
			throw new ServiceException(com.mugsun.core.tool.api.ResultCode.FORBIDDEN);
		}
		if (dto == null || dto.id() == null || dto.enabled() == null) {
			throw new ServiceException("参数不完整。请选择租户和开关状态后再保存");
		}
		boolean on = Boolean.TRUE.equals(dto.enabled());
		String code = tenantService.setCaptchaEnabled(dto.id(), on);
		usualLogService.record("warn", String.valueOf(cn.dev33.satoken.stp.StpUtil.getLoginIdDefaultNull()),
			"租户验证码开关：" + code + " → " + (on ? "开启" : "关闭"), code);
		return R.success(Boolean.TRUE.equals(dto.enabled()) ? "已开启该租户的安全验证" : "已关闭该租户的安全验证");
	}

	public record CaptchaSwitchDTO(Long id, Boolean enabled) {
	}

	@GetMapping("/list")
	public R<List<SysTenant>> list(@RequestParam(required = false) String tenantName,
								   @RequestParam(required = false) String tenantCode,
								   @RequestParam(required = false) Integer status) {
		QueryWrapper query = QueryWrapper.create().orderBy("id", false);
		// 查询条件（值走参数化绑定，名称/编号 LIKE 前后模糊，状态精确匹配）
		if (tenantName != null && !tenantName.isBlank()) {
			query.like("tenant_name", tenantName.trim());
		}
		if (tenantCode != null && !tenantCode.isBlank()) {
			query.like("tenant_code", tenantCode.trim());
		}
		if (status != null) {
			query.eq("status", status);
		}
		return R.data(tenantMapper.selectListByQuery(query));
	}

	@GetMapping("/detail")
	public R<SysTenant> detail(@RequestParam Long id) {
		SysTenant tenant = tenantMapper.selectOneById(id);
		if (tenant == null) {
			throw new ServiceException("这个租户不存在。请刷新列表后再看");
		}
		return R.data(tenant);
	}

	/** 创建租户并初始化默认数据，返回租户编号 */
	@RepeatSubmit
	@PostMapping("/create")
	public R<String> create(@RequestBody SysTenant tenant) {
		String code = tenantService.create(tenant);
		R<String> result = R.data(code);
		result.setMsg("已创建。编号 " + code + "。默认部门、岗位、角色和管理员已准备好，把编号交给对方即可登录");
		return result;
	}

	/** 更新租户信息（含状态/期限/配额/套餐分配），不重新初始化默认数据 */
	@RepeatSubmit
	@PostMapping("/update")
	public R<Void> update(@RequestBody SysTenant tenant) {
		tenantService.update(tenant);
		return R.success("已保存。列表里可以查看这名租户的状态和到期时间");
	}

	@RepeatSubmit
	@PostMapping("/remove")
	public R<Void> remove(@RequestBody List<Long> ids) {
		int count = tenantService.remove(ids);
		return R.success("已删除 " + count + " 个租户。他们的部门、岗位、角色和用户也已清掉，列表里不会再出现");
	}
}
