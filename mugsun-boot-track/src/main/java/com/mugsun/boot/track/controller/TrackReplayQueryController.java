package com.mugsun.boot.track.controller;

import cn.dev33.satoken.annotation.SaCheckLogin;
import cn.dev33.satoken.annotation.SaCheckPermission;
import com.mugsun.boot.common.constant.TrackConstants;
import com.mugsun.boot.log.OperationLog;
import com.mugsun.boot.track.TrackReplayQueryService;
import com.mugsun.core.tool.api.R;
import com.mybatisflex.core.paginate.Page;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.util.List;
import java.util.Map;

/**
 * 会话回放读取 API（/system/track/replay/**，G100）：列表/详情/块内容，全部只读。
 * <p>鉴权：类级 @SaCheckLogin + 方法级 @SaCheckPermission（page/detail 走回放列表码，data 走回放查看码）。
 * 租户隔离由 Flex 行级插件在 mapper 查询自动拼条件（跨租户命中「不存在」）。
 * <p><b>审计</b>：回放是最高敏感数据，块内容读取必写操作日志留痕（谁、何时、看了哪个会话的哪块）。
 * <p><b>分层纪律</b>：权限校验留在本层；DB/存储访问全部委托 {@link TrackReplayQueryService}
 * （@TrackDS 路由埋点库）——在 @TrackDS 切面范围内做权限校验会把业务库查询误路由到埋点库。
 */
@RestController
@RequestMapping("/system/track/replay")
@SaCheckLogin
public class TrackReplayQueryController {

	private final TrackReplayQueryService replayQueryService;

	public TrackReplayQueryController(TrackReplayQueryService replayQueryService) {
		this.replayQueryService = replayQueryService;
	}

	/**
	 * 回放会话分页：records=[{id, sessionId, appKey, tenantId, distinctId, userId, startTime(epoch ms),
	 * durationMs, pageCount, rrwebEvents, sizeBytes, hasError, entryPath, lastSeq, firstEventTs, lastEventTs}]，
	 * startTime 倒序。appKey/hasError 可选过滤（hasError=1 仅含错误会话）。
	 */
	@GetMapping("/page")
	@SaCheckPermission(TrackConstants.PERM_REPLAY_LIST)
	public R<Page<Map<String, Object>>> page(@RequestParam(required = false) String appKey,
											 @RequestParam(required = false) Integer hasError,
											 @RequestParam(defaultValue = "1") long pageNum,
											 @RequestParam(defaultValue = "10") long pageSize) {
		return R.data(replayQueryService.page(appKey, hasError, pageNum, pageSize));
	}

	/** 回放详情：{replay: 同 page 记录结构, blocks: [{seq, key}]}（块键按 seq 推导，供调试；读取走 /data） */
	@GetMapping("/detail")
	@SaCheckPermission(TrackConstants.PERM_REPLAY_LIST)
	public R<Map<String, Object>> detail(@RequestParam String sessionId) {
		return R.data(replayQueryService.detail(sessionId));
	}

	/**
	 * 回放会话事件时间轴打点（G105）：[{eventName, ts(epoch ms), urlPath}] 按 received_at 升序 ≤500。
	 * 元信息非回放内容，走回放列表码、无审计留痕；会话不存在/跨租户 → 空数组（不报错）。
	 */
	@GetMapping("/events")
	@SaCheckPermission(TrackConstants.PERM_REPLAY_LIST)
	public R<List<Map<String, Object>>> events(@RequestParam String appKey, @RequestParam String sessionId) {
		return R.data(replayQueryService.sessionEvents(appKey, sessionId));
	}

	/**
	 * 块内容读取：rrweb 事件数组 JSON 明文（application/json，服务端已解压，前端零解压依赖）。
	 * 最高敏感操作：过回放查看码 + 操作日志留痕「谁看了哪个会话的回放」。
	 */
	@GetMapping("/data")
	@SaCheckPermission(TrackConstants.PERM_REPLAY_VIEW)
	@OperationLog("查看会话回放")
	public void data(@RequestParam String sessionId, @RequestParam int seq,
					 HttpServletResponse response) throws IOException {
		byte[] json = replayQueryService.blockJson(sessionId, seq);
		response.setContentType("application/json;charset=UTF-8");
		response.setContentLength(json.length);
		response.getOutputStream().write(json);
	}
}
