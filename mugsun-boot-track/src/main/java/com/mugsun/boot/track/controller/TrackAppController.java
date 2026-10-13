package com.mugsun.boot.track.controller;

import cn.dev33.satoken.annotation.SaCheckLogin;
import cn.dev33.satoken.annotation.SaCheckPermission;
import cn.dev33.satoken.annotation.SaMode;
import cn.dev33.satoken.stp.StpUtil;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mugsun.boot.common.constant.TrackConstants;
import com.mugsun.boot.track.TrackAdminService;
import com.mugsun.boot.track.TrackIntegrationHealth;
import com.mugsun.boot.track.entity.TrackApp;
import com.mugsun.boot.track.entity.TrackEventDef;
import com.mugsun.core.tool.api.R;
import com.mugsun.core.tool.exception.ServiceException;
import com.mybatisflex.core.paginate.Page;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 埋点接入管理 API：应用 CRUD + 事件定义治理（认领/显示名/说明/停用）。
 * <p>写方法全部显式 @SaCheckPermission（/system/** 写操作 PermissionGuardInterceptor fail-closed 兜底）；
 * submit 单一端点按有无 id 内联细分 add/edit 码（参照 /system/user/submit 先例）。
 * <p><b>分层纪律</b>：权限校验（StpUtil，业务库）必须留在本层；DB 读写全部委托 {@link TrackAdminService}
 * （@TrackDS 路由埋点库）——在 @TrackDS 切面范围内调用权限校验会把业务库查询误路由到埋点库。
 */
@RestController
@RequestMapping("/system/track")
@SaCheckLogin
public class TrackAppController {

	private final TrackAdminService adminService;
	private final ObjectMapper objectMapper;

	private final TrackIntegrationHealth health;

	public TrackAppController(TrackAdminService adminService, ObjectMapper objectMapper, TrackIntegrationHealth health) {
		this.adminService = adminService;
		this.health = health;
		this.objectMapper = objectMapper;
	}

	// ==================== 应用管理 ====================

	/** 应用分页（本租户行级隔离由 Flex 插件自动拼条件） */
	@GetMapping("/app/page")
	@SaCheckPermission(TrackConstants.PERM_APP_LIST)
	public R<Page<TrackApp>> appPage(@RequestParam(defaultValue = "1") long pageNum,
									 @RequestParam(defaultValue = "10") long pageSize,
									 @RequestParam(required = false) String appName) {
		return R.data(adminService.appPage(pageNum, pageSize, appName));
	}

	/** 新增/编辑应用（语义见 TrackAdminService.appSubmit；无 id=新增，有 id=编辑） */
	@PostMapping("/app/submit")
	@SaCheckPermission(value = {TrackConstants.PERM_APP_ADD, TrackConstants.PERM_APP_EDIT}, mode = SaMode.OR)
	public R<TrackApp> appSubmit(@RequestBody(required = false) JsonNode body) {
		if (body == null || body.isNull() || !body.isObject()) {
			throw new ServiceException("请填写应用名称");
		}
		boolean edit = body.hasNonNull("id") && !body.get("id").asText().isBlank()
			&& !"null".equals(body.get("id").asText());
		if (edit && !body.get("id").asText().trim().matches("\\d{1,19}")) {
			throw new ServiceException("应用编号请填写数字，可以从列表里复制");
		}
		TrackApp app = objectMapper.convertValue(body, TrackApp.class);
		// 内联精确分码：注解 OR 仅满足守卫探测，真实校验按新增/编辑分别要码
		StpUtil.checkPermission(app.getId() == null ? TrackConstants.PERM_APP_ADD : TrackConstants.PERM_APP_EDIT);
		TrackApp saved = adminService.appSubmit(app);
		String name = saved.getAppName();
		if (name == null || name.isBlank()) {
			name = "这个应用";
		}
		R<TrackApp> resp = R.data(saved);
		if (edit) {
			resp.setMsg("保存成功。「" + name + "」采集会按这里的名称、采样率和开关执行");
		} else {
			resp.setMsg("保存成功。「" + name + "」接入片段里的应用标识已经生成");
		}
		return resp;
	}

	/** 删除应用（逻辑删除；缓存即时失效后采集端拒收） */
	@PostMapping("/app/remove")
	@SaCheckPermission(TrackConstants.PERM_APP_EDIT)
	public R<Void> appRemove(@RequestBody(required = false) Map<String, Object> body) {
		if (body == null || body.get("id") == null || String.valueOf(body.get("id")).isBlank()
			|| "null".equals(String.valueOf(body.get("id")))) {
			throw new ServiceException("请先在列表里选择要删除的应用");
		}
		String text = String.valueOf(body.get("id")).trim();
		if (!text.matches("\\d{1,19}")) {
			throw new ServiceException("应用编号请填写数字，可以从列表里复制");
		}
		TrackApp removed = adminService.appRemove(Long.valueOf(text));
		String name = removed.getAppName();
		if (name == null || name.isBlank()) {
			name = removed.getAppKey();
		}
		return R.success("已删除「" + name + "」。采集将不再接收这个应用");
	}

	// ==================== 接入自检 ====================

	/**
	 * 单个应用接入自检：最近收到事件时间 + 最近 7 天拒收/异常原因（每条带修复建议）。
	 * 租户隔离：应用经 Flex 租户插件查找，别的租户的 appKey 一律「不存在」。
	 */
	@GetMapping("/app/health")
	@SaCheckPermission(TrackConstants.PERM_APP_LIST)
	public R<Map<String, Object>> appHealth(@RequestParam String appKey) {
		TrackApp app = adminService.requireApp(appKey);
		return R.data(healthOf(app, true));
	}

	/** 本租户全部应用的自检摘要（列表页一次取完：最近收到时间 + 今日/7 天 fail、warn 计数，不含建议正文） */
	@GetMapping("/app/health/summary")
	@SaCheckPermission(TrackConstants.PERM_APP_LIST)
	public R<java.util.List<Map<String, Object>>> appHealthSummary() {
		java.util.List<Map<String, Object>> out = new java.util.ArrayList<>();
		for (TrackApp app : adminService.listApps()) {
			out.add(healthOf(app, false));
		}
		return R.data(out);
	}

	private Map<String, Object> healthOf(TrackApp app, boolean detail) {
		Long lastReceivedAt = health.lastReceivedAt(app.getAppKey());
		String lastReceivedSource = "redis";
		if (lastReceivedAt == null) {
			lastReceivedAt = adminService.lastSeenFromEventDefs(app.getAppKey());
			lastReceivedSource = lastReceivedAt == null ? null : "event_def";
		}
		java.util.List<Map<String, Object>> reasons = health.reasons(app.getAppKey());
		long fail7d = 0;
		long warn7d = 0;
		for (Map<String, Object> r : reasons) {
			if ("fail".equals(r.get("severity"))) {
				fail7d += (Long) r.get("last7d");
			} else {
				warn7d += (Long) r.get("last7d");
			}
		}
		Map<String, Object> m = new java.util.LinkedHashMap<>();
		m.put("appKey", app.getAppKey());
		m.put("appName", app.getAppName());
		m.put("enabled", app.getEnabled() != null && app.getEnabled() == 1);
		m.put("lastReceivedAt", lastReceivedAt);
		m.put("lastReceivedSource", lastReceivedSource);
		m.put("staleAfterMs", TrackIntegrationHealth.STALE_AFTER_MS);
		m.put("fail7d", fail7d);
		m.put("warn7d", warn7d);
		if (detail) {
			m.put("reasons", reasons);
		} else {
			m.put("topCodes", reasons.stream().limit(3).map(r -> r.get("code")).toList());
		}
		return m;
	}

	// ==================== 事件定义治理 ====================

	/** 事件定义分页：appKey 必填；eventName 模糊 / status 精确可选 */
	@GetMapping("/event-def/page")
	@SaCheckPermission(TrackConstants.PERM_APP_LIST)
	public R<Page<TrackEventDef>> eventDefPage(@RequestParam String appKey,
											   @RequestParam(required = false) String eventName,
											   @RequestParam(required = false) Integer status,
											   @RequestParam(defaultValue = "1") long pageNum,
											   @RequestParam(defaultValue = "10") long pageSize) {
		return R.data(adminService.eventDefPage(appKey, eventName, status, pageNum, pageSize));
	}

	/** 事件定义认领：仅 displayName/description/status/owner 可改 */
	@PostMapping("/event-def/submit")
	@SaCheckPermission(TrackConstants.PERM_APP_EDIT)
	public R<Void> eventDefSubmit(@RequestBody(required = false) Map<String, Object> body) {
		if (body == null || body.get("id") == null || String.valueOf(body.get("id")).isBlank()
			|| "null".equals(String.valueOf(body.get("id")))) {
			throw new ServiceException("请先在列表里打开要编辑的事件");
		}
		String text = String.valueOf(body.get("id")).trim();
		if (!text.matches("\\d{1,19}")) {
			throw new ServiceException("事件编号请填写数字，可以从列表里复制");
		}
		return R.success(adminService.eventDefSubmit(Long.valueOf(text), body));
	}
}
