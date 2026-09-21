package com.mugsun.boot.track.controller;

import cn.dev33.satoken.annotation.SaCheckLogin;
import cn.dev33.satoken.annotation.SaCheckPermission;
import com.mugsun.boot.common.constant.TrackConstants;
import com.mugsun.boot.track.TrackAnalysisService;
import com.mugsun.boot.track.TrackFunnelService;
import com.mugsun.boot.track.TrackRetentionService;
import com.mugsun.core.tool.api.R;
import com.mybatisflex.core.paginate.Page;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 埋点分析 API（/system/track/**）：概览/趋势/页面/事件/性能/错误/在线，全部只读。
 * <p>鉴权：类级 @SaCheckLogin + 方法级 @SaCheckPermission（权限码见 TrackConstants，V63 种子锚点）。
 * 数据源：全部查询走 track 独立库（{@code TrackAnalysisService} 类级 @TrackDS）；
 * 租户隔离由显式租户条件（与 Flex 插件同语义）保证，Redis 通道先校验应用归属。
 * <p>看板查询统一 JetCache 30–60s（实时流/在线人数除外——走 Redis 实时通道）。
 */
@RestController
@RequestMapping("/system/track")
@SaCheckLogin
public class TrackAnalysisController {

	private final TrackAnalysisService analysisService;
	private final TrackFunnelService funnelService;
	private final TrackRetentionService retentionService;

	public TrackAnalysisController(TrackAnalysisService analysisService, TrackFunnelService funnelService,
								   TrackRetentionService retentionService) {
		this.analysisService = analysisService;
		this.funnelService = funnelService;
		this.retentionService = retentionService;
	}

	/**
	 * 概览：cards（pv/uv/sessionCount/eventCount/avgSessionDurationMs/bounceRate）
	 * + referrerDist/deviceDist/browserTop（PV 口径 Top10）。
	 * 今日部分当日分区直算，历史部分查 stats_day（界限见 TrackAnalysisService javadoc）。
	 */
	@GetMapping("/overview")
	@SaCheckPermission(TrackConstants.PERM_OVERVIEW_LIST)
	public R<Map<String, Object>> overview(@RequestParam String appKey,
										   @RequestParam(required = false) Integer days) {
		return R.data(analysisService.overview(appKey, days));
	}

	/**
	 * 趋势时间序列：days<=2 返回 5 分钟粒度 [{time(epoch ms), pv, eventCount, sessionCount}]；
	 * days>2 返回日粒度 [{date(yyyy-MM-dd), pv, uv, sessionCount, bounceCount, eventCount}]。
	 * dimType：overview/event/page/referrer/device（默认 overview）；dimKey 可选（指定维度值，overview 忽略）。
	 */
	@GetMapping("/trend")
	@SaCheckPermission(TrackConstants.PERM_OVERVIEW_LIST)
	public R<List<Map<String, Object>>> trend(@RequestParam String appKey,
											  @RequestParam(required = false) Integer days,
											  @RequestParam(required = false) String dimType,
											  @RequestParam(required = false) String dimKey) {
		return R.data(analysisService.trend(appKey, days, dimType, dimKey));
	}

	/** Top 页面：[{pagePath, pv, uv, avgDurationMs}]，按 pv 降序；pagePath 为路由模板（空回退原始路径） */
	@GetMapping("/pages")
	@SaCheckPermission(TrackConstants.PERM_OVERVIEW_LIST)
	public R<List<Map<String, Object>>> pages(@RequestParam String appKey,
											  @RequestParam(required = false) Integer days,
											  @RequestParam(required = false) Integer limit) {
		return R.data(analysisService.pages(appKey, days, limit));
	}

	/** 事件分析分页：records=[{eventName, eventCount, sessionCount, uv, lastTime(epoch ms)}]，按次数降序 */
	@GetMapping("/events/page")
	@SaCheckPermission(TrackConstants.PERM_EVENT_LIST)
	public R<Page<Map<String, Object>>> eventsPage(@RequestParam String appKey,
												   @RequestParam(required = false) String eventName,
												   @RequestParam(required = false) Integer days,
												   @RequestParam(defaultValue = "1") long pageNum,
												   @RequestParam(defaultValue = "10") long pageSize) {
		return R.data(analysisService.eventsPage(appKey, eventName, days, pageNum, pageSize));
	}

	/** 实时事件流（Redis Stream 尾部，不等落库）：[{id, eventId, eventName, ts, distinctId, sessionId, userId, urlPath}] */
	@GetMapping("/events/realtime")
	@SaCheckPermission(TrackConstants.PERM_EVENT_LIST)
	public R<List<Map<String, Object>>> eventsRealtime(@RequestParam String appKey,
													   @RequestParam(required = false) Integer limit) {
		return R.data(analysisService.eventsRealtime(appKey, limit));
	}

	/** 当前在线人数：{online, windowSeconds}（ZSET 5 分钟窗） */
	@GetMapping("/online")
	@SaCheckPermission(TrackConstants.PERM_OVERVIEW_LIST)
	public R<Map<String, Object>> online(@RequestParam String appKey) {
		return R.data(analysisService.online(appKey));
	}

	/** Web Vitals 分位：[{metric, count, avg, p50, p75, p95}]（直方图插值；CLS 千分制，其余毫秒） */
	@GetMapping("/vitals")
	@SaCheckPermission(TrackConstants.PERM_PERF_LIST)
	public R<List<Map<String, Object>>> vitals(@RequestParam String appKey,
											   @RequestParam(required = false) Integer days,
											   @RequestParam(required = false) String routePath) {
		return R.data(analysisService.vitals(appKey, days, routePath));
	}

	/** 错误分组分页：records=[{fingerprint, eventCount, sessionCount, firstTime, lastTime, message}]，按最近发生降序 */
	@GetMapping("/errors/page")
	@SaCheckPermission(TrackConstants.PERM_ERROR_LIST)
	public R<Page<Map<String, Object>>> errorsPage(@RequestParam String appKey,
												   @RequestParam(required = false) Integer days,
												   @RequestParam(defaultValue = "1") long pageNum,
												   @RequestParam(defaultValue = "10") long pageSize) {
		return R.data(analysisService.errorsPage(appKey, days, pageNum, pageSize));
	}

	/** 错误组内明细：records=[{eventId, sessionId, distinctId, urlPath, routePath, time, message, release, stack, props(JSON 文本)}] */
	@GetMapping("/errors/detail")
	@SaCheckPermission(TrackConstants.PERM_ERROR_LIST)
	public R<Page<Map<String, Object>>> errorDetail(@RequestParam String appKey,
													@RequestParam String fingerprint,
													@RequestParam(required = false) Integer days,
													@RequestParam(defaultValue = "1") long pageNum,
													@RequestParam(defaultValue = "10") long pageSize) {
		return R.data(analysisService.errorDetail(appKey, fingerprint, days, pageNum, pageSize));
	}

	/**
	 * 地理分析（G106）：{regions:[{region, pv, uv, eventCount}], points:[{lon, lat, eventName, ts, urlPath}], geoCount}。
	 * 属地按 IP 省份归一（空/内网独立桶）；精确点仅服务端圆整后的 geo_lon/geo_lat 列，上限 2000。
	 */
	@GetMapping("/geo")
	@SaCheckPermission(TrackConstants.PERM_OVERVIEW_LIST)
	public R<Map<String, Object>> geo(@RequestParam String appKey,
									  @RequestParam(required = false) Integer days) {
		return R.data(analysisService.geo(appKey, days));
	}

	/**
	 * 漏斗分析（G103，§20.1）：{steps:[{eventName, count}...按入参序], days, windowHours, actor:"merged"}。
	 * actor = identity 归并；有序非紧邻匹配；每层须在前一步后 windowHours（1/24/168，默认 24）小时内触达；
	 * days 默认 7 上限 30；steps 逗号分隔 2..5 个合法事件名（转化率前端算）。
	 */
	@GetMapping("/funnel")
	@SaCheckPermission(TrackConstants.PERM_FUNNEL_LIST)
	public R<Map<String, Object>> funnel(@RequestParam String appKey,
										 @RequestParam String steps,
										 @RequestParam(required = false) Integer days,
										 @RequestParam(required = false) Long windowHours) {
		return R.data(funnelService.funnel(appKey, steps, days, windowHours));
	}

	/**
	 * 留存分析（G103，§20.1 新客留存口径）：{rows:[{cohortDate("yyyy-MM-dd"), cohortSize,
	 * retained:{offset数字字符串: 人数}}...按 cohortDate 升序], days}。
	 * cohort = 窗口内新客（首活跃日 ∈ [todayUtc-(days-1), todayUtc] 且 > 回看窗首日，30 天回看截断排除）；
	 * 活跃 = 当天任意事件；日切 UTC 墙钟；days 默认 7 上限 30。
	 */
	@GetMapping("/retention")
	@SaCheckPermission(TrackConstants.PERM_RETENTION_LIST)
	public R<Map<String, Object>> retention(@RequestParam String appKey,
											@RequestParam(required = false) Integer days) {
		return R.data(retentionService.retention(appKey, days));
	}
}
