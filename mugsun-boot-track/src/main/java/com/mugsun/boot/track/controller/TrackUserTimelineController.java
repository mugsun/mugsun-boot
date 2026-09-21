package com.mugsun.boot.track.controller;

import cn.dev33.satoken.annotation.SaCheckLogin;
import cn.dev33.satoken.annotation.SaCheckPermission;
import com.mugsun.boot.common.constant.TrackConstants;
import com.mugsun.boot.log.OperationLog;
import com.mugsun.boot.track.TrackUserTimelineService;
import com.mugsun.core.tool.api.R;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.util.Map;

/**
 * 用户细查 API（/system/track/user/**，G102）：行为时间线游标分页 + 接口响应体读取，全部只读。
 * <p>鉴权：类级 @SaCheckLogin + 方法级 @SaCheckPermission（timeline 走用户细查码，api-body 走响应体查看码）。
 * 租户隔离由服务层显式租户条件保证（跨租户读取命中「不存在」/空页）。
 * <p><b>审计</b>：接口响应体是最高敏感数据，读取必写操作日志留痕（谁、何时、看了哪个事件的响应体）。
 * <p><b>分层纪律</b>：权限校验留在本层；DB/存储访问全部委托 {@link TrackUserTimelineService}
 * （@TrackDS 路由埋点库）——在 @TrackDS 切面范围内做权限校验会把业务库查询误路由到埋点库。
 */
@RestController
@RequestMapping("/system/track/user")
@SaCheckLogin
public class TrackUserTimelineController {

	private final TrackUserTimelineService timelineService;

	public TrackUserTimelineController(TrackUserTimelineService timelineService) {
		this.timelineService = timelineService;
	}

	/**
	 * 行为时间线：{records: [{eventId, eventName, ts, clientTs, urlPath, routePath, durationMs, sessionId,
	 * props, hasReplay, hasApiBody}], nextCursor}——按 received_at+id 游标倒序；nextCursor 非空即还有下一页。
	 * userId/distinctId 二选一（userId 优先，经 track_identity 归并该用户全部匿名期 distinct_id 行为）；
	 * 范围硬限 ≤7 天（超界 400）。
	 */
	@GetMapping("/timeline")
	@SaCheckPermission(TrackConstants.PERM_USER_LIST)
	public R<Map<String, Object>> timeline(@RequestParam String appKey,
										   @RequestParam(required = false) Long userId,
										   @RequestParam(required = false) String distinctId,
										   @RequestParam Long startTs,
										   @RequestParam Long endTs,
										   @RequestParam(required = false) String cursor,
										   @RequestParam(required = false) Integer pageSize) {
		return R.data(timelineService.timeline(appKey, userId, distinctId, startTs, endTs, cursor, pageSize));
	}

	/**
	 * 接口响应体读取：JSON 明文（application/json，服务端已解压，前端零解压依赖，非 R 信封）。
	 * 最高敏感操作：过响应体查看码 + 操作日志留痕「查看接口响应体」；
	 * 未采集/已过保留期/跨租户一律 400「body 未采集或已清理」。
	 */
	@GetMapping("/api-body")
	@SaCheckPermission(TrackConstants.PERM_USER_VIEW_BODY)
	@OperationLog("查看接口响应体")
	public void apiBody(@RequestParam String eventId, HttpServletResponse response) throws IOException {
		byte[] json = timelineService.bodyJson(eventId);
		response.setContentType("application/json;charset=UTF-8");
		response.setContentLength(json.length);
		response.getOutputStream().write(json);
	}
}
