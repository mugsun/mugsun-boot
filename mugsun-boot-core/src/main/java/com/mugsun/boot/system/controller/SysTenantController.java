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

	/**
	 * 租户验证码开关 + 启用题型：仅平台超管可改，写审计日志。
	 * types 缺省 = 不改；types 为 null 或 [] = 清回平台默认（NULL）；否则只能是 5 个合法值。
	 * 启用集合不含 SLIDER/ROTATE 时仍保存，但响应 data.warning 提示 keyboard 用户会收到 40015；
	 * 只启用 ROTATE 或只启用 ICON_CLICK 时同样保存并提示该题型只能部分抵御自动求解（辅助题型）。
	 */
	@RepeatSubmit
	@PostMapping("/captcha")
	public R<java.util.Map<String, Object>> captchaSwitch(@RequestBody CaptchaSwitchDTO dto) {
		if (!com.mugsun.boot.tenant.TenantContext.isPlatformSuperAdmin()) {
			throw new ServiceException(com.mugsun.core.tool.api.ResultCode.FORBIDDEN);
		}
		if (dto == null || dto.id() == null || dto.enabled() == null) {
			throw new ServiceException("参数不完整。请选择租户和开关状态后再保存");
		}
		boolean on = Boolean.TRUE.equals(dto.enabled());
		String types = null;
		String warning = null;
		com.fasterxml.jackson.databind.JsonNode raw = dto.types();
		if (raw != null) {
			if (raw.isNull() || (raw.isArray() && raw.isEmpty())) {
				types = TenantService.CLEAR_CAPTCHA_TYPES;
			} else {
				java.util.List<String> list = new java.util.ArrayList<>();
				if (raw.isArray()) {
					raw.forEach(n -> list.add(n.isTextual() ? n.asText() : "\u0000"));
				}
				java.util.Set<com.mugsun.boot.captcha.CaptchaType> parsed = com.mugsun.boot.captcha.CaptchaType.parseAll(list);
				if (!raw.isArray() || parsed == null || parsed.size() != list.stream().distinct().count()) {
					throw new ServiceException("验证码类型无效。可选：SLIDER、ROTATE、WORD_CLICK、ICON_CLICK、JIGSAW；传空数组恢复平台默认");
				}
				types = com.mugsun.boot.captcha.CaptchaType.toCsv(parsed);
				warning = captchaTypesWarning(parsed);
			}
		}
		String code = tenantService.setCaptcha(dto.id(), on, types);
		String typeLog = types == null ? "" : TenantService.CLEAR_CAPTCHA_TYPES.equals(types) ? "，类型 恢复平台默认" : "，类型 " + types;
		usualLogService.record("warn", String.valueOf(cn.dev33.satoken.stp.StpUtil.getLoginIdDefaultNull()),
			"租户验证码开关：" + code + " → " + (on ? "开启" : "关闭") + typeLog, code);
		java.util.Map<String, Object> data = new java.util.LinkedHashMap<>();
		if (warning != null) {
			data.put("warning", warning);
		}
		R<java.util.Map<String, Object>> r = R.data(data.isEmpty() ? null : data);
		r.setMsg(on ? "已开启该租户的安全验证" : "已关闭该租户的安全验证");
		return r;
	}

	static final String WARN_NO_KEYBOARD = "未启用 SLIDER 或 ROTATE：键盘/读屏用户将无法完成验证（返回 40015「未启用无障碍验证方式」）";
	static final String WARN_ROTATE_ONLY = "只启用了 ROTATE：旋转题目前只能部分抵御自动求解（按边缘匹配约 60% 可解），不应作为唯一防线，建议同时启用 SLIDER 或其他题型";

	static final String WARN_ICON_CLICK_ONLY = "只启用了 ICON_CLICK：图标库公开，图标点选只能部分抵御自动求解，不应作为唯一防线，建议同时启用 SLIDER 或其他题型";

	/** 保存题型时的提示（仍保存）；多条用「；」连接，无提示返回 null */
	static String captchaTypesWarning(java.util.Set<com.mugsun.boot.captcha.CaptchaType> parsed) {
		java.util.List<String> w = new java.util.ArrayList<>();
		if (java.util.Collections.disjoint(parsed, com.mugsun.boot.captcha.CaptchaType.KEYBOARD)) {
			w.add(WARN_NO_KEYBOARD);
		}
		if (parsed.equals(java.util.EnumSet.of(com.mugsun.boot.captcha.CaptchaType.ROTATE))) {
			w.add(WARN_ROTATE_ONLY);
		}
		if (parsed.equals(java.util.EnumSet.of(com.mugsun.boot.captcha.CaptchaType.ICON_CLICK))) {
			w.add(WARN_ICON_CLICK_ONLY);
		}
		return w.isEmpty() ? null : String.join("；", w);
	}

	/** types 用 JsonNode 区分「缺省（不改）」与「显式 null（清回默认）」 */
	public record CaptchaSwitchDTO(Long id, Boolean enabled, com.fasterxml.jackson.databind.JsonNode types) {
		/** 防重复提交按 toString 取指纹：缺省 types 与显式 null 是两种操作，不能撞成同一指纹 */
		@Override
		public String toString() {
			String t = types == null ? "<unset>" : types.isNull() ? "<null>" : types.toString();
			return "CaptchaSwitchDTO[id=" + id + ", enabled=" + enabled + ", types=" + t + "]";
		}
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
