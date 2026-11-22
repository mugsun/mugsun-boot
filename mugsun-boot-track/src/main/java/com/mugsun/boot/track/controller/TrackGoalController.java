package com.mugsun.boot.track.controller;

import cn.dev33.satoken.annotation.SaCheckLogin;
import cn.dev33.satoken.annotation.SaCheckPermission;
import cn.dev33.satoken.stp.StpUtil;
import com.mugsun.boot.common.constant.TrackConstants;
import com.mugsun.boot.track.TrackGoalService;
import com.mugsun.core.tool.api.R;
import com.mugsun.core.tool.exception.ServiceException;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 目标转化：查各个目标的转化情况，新增、编辑、删除目标。
 * <p>查看要 {@link TrackConstants#PERM_GOAL_LIST}，写操作要 {@link TrackConstants#PERM_GOAL_EDIT}
 * （/system/** 的写操作没有显式权限会被 PermissionGuardInterceptor 拦掉）。
 * <p><b>分层纪律</b>：权限校验（查业务库）留在本层；业务和数据库读写都交给 {@link TrackGoalService}
 * （@TrackDS 路由到埋点库）——在 @TrackDS 范围内做权限校验，会把业务库的查询误路由到埋点库。
 */
@RestController
@RequestMapping("/system/track/goal")
@SaCheckLogin
public class TrackGoalController {

	private final TrackGoalService goalService;

	public TrackGoalController(TrackGoalService goalService) {
		this.goalService = goalService;
	}

	/**
	 * 一个应用所有目标的转化情况：
	 * {days, uv, goals:[{id, goalName, goalType, eventName, pagePath, uv, count, rate}]}。
	 * 外层 uv 是统计范围内的全部访客数；每个目标的 uv 是转化人数、count 是转化次数、rate 是转化率（0 到 1）。
	 * days 缺省 7，只支持 1..{@value TrackConstants#GOAL_DAYS_MAX}。目标的定义也在这里返回，页面不用另查一次。
	 * 算法见 TrackGoalService。
	 */
	@GetMapping("/conversions")
	@SaCheckPermission(TrackConstants.PERM_GOAL_LIST)
	public R<Map<String, Object>> conversions(@RequestParam(required = false) String appKey,
											  @RequestParam(required = false) Integer days) {
		return R.data(goalService.conversions(appKey, days));
	}

	/**
	 * 新增或编辑目标：{id 可空, appKey, goalName, goalType: event|page, eventName, pagePath}。
	 * 没有 id 是新增；有 id 是编辑，这时 appKey 不能改，没传的字段保持原样。
	 */
	@PostMapping("/submit")
	@SaCheckPermission(TrackConstants.PERM_GOAL_EDIT)
	public R<Void> submit(@RequestBody(required = false) Map<String, Object> body) {
		Map<String, Object> in = body == null ? Map.of() : body;
		String idText = text(in, "id");
		Long id = idText == null || idText.isBlank() ? null : parseId(idText);
		return R.success(goalService.submit(id, text(in, "appKey"), text(in, "goalName"), text(in, "goalType"),
			text(in, "eventName"), text(in, "pagePath"), StpUtil.getLoginIdAsLong()));
	}

	/** 删除目标：{id}。只删这条定义，已经上报的事件不动 */
	@PostMapping("/remove")
	@SaCheckPermission(TrackConstants.PERM_GOAL_EDIT)
	public R<Void> remove(@RequestBody(required = false) Map<String, Object> body) {
		String idText = body == null ? null : text(body, "id");
		if (idText == null || idText.isBlank()) {
			throw new ServiceException("请先在列表里选择要删除的目标");
		}
		return R.success(goalService.remove(parseId(idText)));
	}

	/** 请求体里的一个字段，统一按文本取；没传或传了 null 都是 null */
	private static String text(Map<String, Object> body, String key) {
		Object value = body.get(key);
		return value == null ? null : String.valueOf(value);
	}

	/** 目标编号：只认数字，超出范围也按不对处理 */
	private static long parseId(String idText) {
		String text = idText.trim();
		if (text.matches("\\d{1,19}")) {
			try {
				return Long.parseLong(text);
			} catch (NumberFormatException ignored) {
				// 19 位数字也可能超过 long 的上限，走到下面统一提示
			}
		}
		throw new ServiceException("目标编号请填写数字，可以从列表里复制");
	}
}
