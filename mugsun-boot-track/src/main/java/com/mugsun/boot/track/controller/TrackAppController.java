package com.mugsun.boot.track.controller;

import cn.dev33.satoken.annotation.SaCheckLogin;
import cn.dev33.satoken.annotation.SaCheckPermission;
import cn.dev33.satoken.annotation.SaMode;
import cn.dev33.satoken.stp.StpUtil;
import com.mugsun.boot.common.constant.TrackConstants;
import com.mugsun.boot.track.TrackAdminService;
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

	public TrackAppController(TrackAdminService adminService) {
		this.adminService = adminService;
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
	public R<TrackApp> appSubmit(@RequestBody TrackApp body) {
		// 内联精确分码：注解 OR 仅满足守卫探测，真实校验按新增/编辑分别要码
		StpUtil.checkPermission(body.getId() == null ? TrackConstants.PERM_APP_ADD : TrackConstants.PERM_APP_EDIT);
		return R.data(adminService.appSubmit(body));
	}

	/** 删除应用（逻辑删除；缓存即时失效后采集端拒收） */
	@PostMapping("/app/remove")
	@SaCheckPermission(TrackConstants.PERM_APP_EDIT)
	public R<Void> appRemove(@RequestBody Map<String, Object> body) {
		Long id = body.get("id") == null ? null : Long.valueOf(body.get("id").toString());
		if (id == null) {
			throw new ServiceException("缺少 id");
		}
		adminService.appRemove(id);
		return R.success("已删除");
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
	public R<TrackEventDef> eventDefSubmit(@RequestBody TrackEventDef body) {
		return R.data(adminService.eventDefSubmit(body));
	}
}
