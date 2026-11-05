package com.mugsun.boot.track;

import cn.dev33.satoken.stp.StpUtil;
import com.mugsun.boot.common.constant.TrackConstants;
import com.mugsun.boot.common.module.TrackAskIntent;
import com.mugsun.boot.common.module.TrackAskLlmGateway;
import com.mugsun.core.tool.exception.ServiceException;
import com.mybatisflex.core.paginate.Page;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 埋点问数据编排：抽意图 → 意图二次权限 → 只读调用既有分析/漏斗/留存 → 拼 summary 与相关回放。
 * <p>本类<b>不加</b> {@link TrackDS}：入口权限与 {@link StpUtil} 二次鉴权须走业务库会话；
 * 埋点库查询全部委托已有 {@code @TrackDS} 服务。数字只来自分析服务，禁止 LLM 编造。
 */
@Service
public class TrackAskService {

	private static final Logger log = LoggerFactory.getLogger(TrackAskService.class);
	private static final int QUESTION_MAX = 500;
	private static final int REPLAY_LIMIT = 5;
	private static final String SOURCE = "track-analysis";

	private final TrackAnalysisService analysisService;
	private final TrackFunnelService funnelService;
	private final TrackRetentionService retentionService;
	private final TrackReplayQueryService replayQueryService;
	private final TrackAskRuleIntentParser ruleParser;
	private final ObjectProvider<TrackAskLlmGateway> llmGateway;

	public TrackAskService(TrackAnalysisService analysisService, TrackFunnelService funnelService,
						   TrackRetentionService retentionService, TrackReplayQueryService replayQueryService,
						   TrackAskRuleIntentParser ruleParser, ObjectProvider<TrackAskLlmGateway> llmGateway) {
		this.analysisService = analysisService;
		this.funnelService = funnelService;
		this.retentionService = retentionService;
		this.replayQueryService = replayQueryService;
		this.ruleParser = ruleParser;
		this.llmGateway = llmGateway;
	}

	public Map<String, Object> ask(String appKey, String question, Integer days) {
		String q = requireQuestion(question);
		Integer daysHint = normalizeDaysHint(days);
		String key = analysisService.requireAppAccessible(appKey);
		List<String> warnings = new ArrayList<>();

		TrackAskIntent intent = resolveIntent(q, daysHint, warnings);
		Map<String, Object> params = normalizeParams(intent.params(), daysHint);

		Map<String, Object> out = new LinkedHashMap<>();
		out.put("question", q);
		out.put("intent", intent.intent());
		out.put("params", params);
		out.put("source", SOURCE);
		out.put("resultType", null);
		out.put("summary", null);
		out.put("metrics", null);
		out.put("series", null);
		out.put("table", null);
		out.put("raw", null);
		out.put("replays", List.of());
		out.put("replayEmpty", true);
		out.put("replayDenied", false);
		out.put("warnings", warnings);

		if (intent.unsupported()) {
			out.put("resultType", "unsupported");
			out.put("summary", intent.warning() != null ? intent.warning()
				: "暂时无法理解这个问题，请换个说法，例如「最近 7 天概览」或「今日在线人数」");
			attachReplays(out, key);
			return out;
		}

		requireIntentPermission(intent.intent());
		dispatch(out, intent.intent(), key, params);
		attachReplays(out, key);
		return out;
	}

	private TrackAskIntent resolveIntent(String question, Integer daysHint, List<String> warnings) {
		TrackAskLlmGateway gateway = llmGateway.getIfAvailable();
		if (gateway != null) {
			try {
				TrackAskIntent llm = gateway.extract(question, daysHint);
				if (llm != null && !llm.unsupported()) {
					if (llm.warning() != null && !llm.warning().isBlank()) {
						warnings.add(llm.warning());
					}
					return llm;
				}
				if (llm != null && llm.unsupported()) {
					// LLM 明确不支持时仍尝试规则（确定性、不编数）；两边都不成再 unsupported
					warnings.add(llm.warning() != null ? llm.warning() : "模型未能识别意图，已尝试规则解析");
				}
			} catch (RuntimeException ex) {
				log.warn("问数据 LLM 意图抽取失败，降级规则解析：{}", ex.getMessage());
				warnings.add("模型调用失败，已改用规则解析意图（数字仍只来自埋点分析）");
			}
		}
		TrackAskIntent rule = ruleParser.parse(question, daysHint);
		if (rule.warning() != null && !rule.warning().isBlank() && rule.unsupported()) {
			warnings.add(rule.warning());
		}
		return rule;
	}

	private void requireIntentPermission(String intent) {
		String perm = switch (intent) {
			case "overview", "trend", "pages", "online", "geo" -> TrackConstants.PERM_OVERVIEW_LIST;
			case "events" -> TrackConstants.PERM_EVENT_LIST;
			case "vitals" -> TrackConstants.PERM_PERF_LIST;
			case "errors" -> TrackConstants.PERM_ERROR_LIST;
			case "funnel" -> TrackConstants.PERM_FUNNEL_LIST;
			case "retention" -> TrackConstants.PERM_RETENTION_LIST;
			default -> null;
		};
		if (perm != null) {
			StpUtil.checkPermission(perm);
		}
	}

	@SuppressWarnings("unchecked")
	private void dispatch(Map<String, Object> out, String intent, String appKey, Map<String, Object> params) {
		Integer days = asInt(params.get("days"));
		switch (intent) {
			case "overview" -> {
				Map<String, Object> data = analysisService.overview(appKey, days);
				Map<String, Object> cards = (Map<String, Object>) data.get("cards");
				out.put("resultType", "metrics");
				out.put("metrics", cards);
				out.put("raw", data);
				out.put("summary", summaryOverview(cards, days));
			}
			case "trend" -> {
				List<Map<String, Object>> series = analysisService.trend(appKey, days,
					text(params.get("dimType")), text(params.get("dimKey")));
				out.put("resultType", "series");
				out.put("series", series);
				out.put("summary", "近 " + days + " 天趋势共 " + series.size() + " 个点（数字来自埋点分析）");
			}
			case "pages" -> {
				Integer limit = asInt(params.get("limit"));
				List<Map<String, Object>> table = analysisService.pages(appKey, days, limit);
				out.put("resultType", "table");
				out.put("table", table);
				out.put("summary", "近 " + days + " 天热门页面 Top " + table.size());
			}
			case "events" -> {
				Page<Map<String, Object>> page = analysisService.eventsPage(appKey,
					text(params.get("eventName")), days, 1L, 20L);
				out.put("resultType", "table");
				out.put("table", page.getRecords());
				out.put("raw", Map.of("totalRow", page.getTotalRow(), "pageNumber", page.getPageNumber(),
					"pageSize", page.getPageSize()));
				out.put("summary", "近 " + days + " 天事件共 " + page.getTotalRow() + " 种");
			}
			case "online" -> {
				Map<String, Object> online = analysisService.online(appKey);
				out.put("resultType", "metrics");
				out.put("metrics", online);
				out.put("summary", "当前在线 " + online.getOrDefault("online", 0) + " 人（"
					+ online.getOrDefault("windowSeconds", 0) + " 秒窗）");
			}
			case "geo" -> {
				Map<String, Object> geo = analysisService.geo(appKey, days);
				out.put("resultType", "raw");
				out.put("raw", geo);
				Object regions = geo.get("regions");
				int n = regions instanceof List<?> list ? list.size() : 0;
				out.put("summary", "近 " + days + " 天地域桶 " + n + " 个，带坐标事件 "
					+ geo.getOrDefault("geoCount", 0));
			}
			case "vitals" -> {
				List<Map<String, Object>> table = analysisService.vitals(appKey, days,
					text(params.get("routePath")));
				out.put("resultType", "table");
				out.put("table", table);
				out.put("summary", "近 " + days + " 天 Web Vitals " + table.size() + " 项");
			}
			case "errors" -> {
				Page<Map<String, Object>> page = analysisService.errorsPage(appKey, days, 1L, 20L);
				out.put("resultType", "table");
				out.put("table", page.getRecords());
				out.put("raw", Map.of("totalRow", page.getTotalRow()));
				out.put("summary", "近 " + days + " 天错误指纹 " + page.getTotalRow() + " 组");
			}
			case "funnel" -> {
				String steps = text(params.get("steps"));
				if (steps == null || steps.isBlank()) {
					throw new ServiceException("漏斗需要至少 2 个步骤事件名。请写成「漏斗：view,submit,done」");
				}
				Long windowHours = asLong(params.get("windowHours"));
				Map<String, Object> funnel = funnelService.funnel(appKey, steps, days, windowHours);
				out.put("resultType", "raw");
				out.put("raw", funnel);
				Object stepRows = funnel.get("steps");
				int n = stepRows instanceof List<?> list ? list.size() : 0;
				out.put("summary", "漏斗 " + n + " 步（窗口 "
					+ funnel.getOrDefault("windowHours", TrackConstants.FUNNEL_WINDOW_DEFAULT_HOURS) + " 小时）");
			}
			case "retention" -> {
				Map<String, Object> retention = retentionService.retention(appKey, days);
				out.put("resultType", "raw");
				out.put("raw", retention);
				Object rows = retention.get("rows");
				int n = rows instanceof List<?> list ? list.size() : 0;
				out.put("summary", "留存 cohort " + n + " 天（近 " + retention.getOrDefault("days", days) + " 天窗）");
			}
			default -> {
				out.put("intent", TrackAskIntent.UNSUPPORTED);
				out.put("resultType", "unsupported");
				out.put("summary", "暂时无法理解这个问题，请换个说法");
			}
		}
	}

	private void attachReplays(Map<String, Object> out, String appKey) {
		if (!StpUtil.hasPermission(TrackConstants.PERM_REPLAY_LIST)) {
			out.put("replays", List.of());
			out.put("replayEmpty", true);
			out.put("replayDenied", true);
			return;
		}
		out.put("replayDenied", false);
		List<Map<String, Object>> replays = new ArrayList<>(REPLAY_LIMIT);
		try {
			Page<Map<String, Object>> errPage = replayQueryService.page(appKey, 1, 1L, REPLAY_LIMIT);
			appendReplayRows(replays, errPage.getRecords(), appKey);
			if (replays.size() < REPLAY_LIMIT) {
				Page<Map<String, Object>> all = replayQueryService.page(appKey, null, 1L, REPLAY_LIMIT);
				appendReplayRows(replays, all.getRecords(), appKey);
			}
		} catch (ServiceException ex) {
			// 应用不存在等：与分析同口径上抛；其它业务提示保留空回放
			if (ex.getMessage() != null && ex.getMessage().contains("应用")) {
				throw ex;
			}
			log.debug("问数据挂回放失败：{}", ex.getMessage());
		} catch (RuntimeException ex) {
			log.warn("问数据挂回放异常：{}", ex.getMessage());
		}
		out.put("replays", replays);
		out.put("replayEmpty", replays.isEmpty());
	}

	private static void appendReplayRows(List<Map<String, Object>> dest, List<Map<String, Object>> rows, String appKey) {
		if (rows == null) {
			return;
		}
		for (Map<String, Object> row : rows) {
			if (dest.size() >= REPLAY_LIMIT) {
				return;
			}
			String sid = text(row.get("sessionId"));
			if (sid == null) {
				continue;
			}
			boolean dup = false;
			for (Map<String, Object> existing : dest) {
				if (sid.equals(existing.get("sessionId"))) {
					dup = true;
					break;
				}
			}
			if (dup) {
				continue;
			}
			Map<String, Object> slim = new LinkedHashMap<>();
			slim.put("sessionId", sid);
			slim.put("appKey", appKey);
			slim.put("startTime", row.get("startTime"));
			slim.put("entryPath", row.get("entryPath"));
			slim.put("hasError", row.get("hasError"));
			slim.put("durationMs", row.get("durationMs"));
			dest.add(slim);
		}
	}

	private static String summaryOverview(Map<String, Object> cards, Integer days) {
		if (cards == null) {
			return "近 " + days + " 天暂无概览数据";
		}
		return "近 " + days + " 天：PV " + cards.getOrDefault("pv", 0)
			+ "，UV " + cards.getOrDefault("uv", 0)
			+ "，会话 " + cards.getOrDefault("sessionCount", 0)
			+ "，事件 " + cards.getOrDefault("eventCount", 0);
	}

	private static String requireQuestion(String question) {
		if (question == null || question.isBlank()) {
			throw new ServiceException("请输入要问的问题");
		}
		String q = question.trim();
		if (q.length() > QUESTION_MAX) {
			throw new ServiceException("问题请控制在 " + QUESTION_MAX + " 字以内");
		}
		return q;
	}

	private static Integer normalizeDaysHint(Integer days) {
		if (days == null) {
			return null;
		}
		if (days < 1 || days > TrackConstants.ANALYSIS_DAYS_MAX) {
			throw new ServiceException("统计天数只支持 1 到 " + TrackConstants.ANALYSIS_DAYS_MAX + " 天");
		}
		return days;
	}

	private static Map<String, Object> normalizeParams(Map<String, Object> raw, Integer daysHint) {
		Map<String, Object> params = new LinkedHashMap<>();
		params.put("days", daysHint != null ? daysHint : (asInt(raw.get("days")) == null ? 7 : asInt(raw.get("days"))));
		params.put("eventName", text(raw.get("eventName")));
		params.put("dimType", text(raw.get("dimType")));
		params.put("dimKey", text(raw.get("dimKey")));
		params.put("steps", text(raw.get("steps")));
		params.put("windowHours", asLong(raw.get("windowHours")));
		params.put("fingerprint", text(raw.get("fingerprint")));
		params.put("routePath", text(raw.get("routePath")));
		params.put("limit", asInt(raw.get("limit")));
		Integer d = asInt(params.get("days"));
		if (d != null && (d < 1 || d > TrackConstants.ANALYSIS_DAYS_MAX)) {
			params.put("days", 7);
		}
		return params;
	}

	private static String text(Object raw) {
		if (raw == null) {
			return null;
		}
		String s = String.valueOf(raw).trim();
		if (s.isEmpty() || "null".equalsIgnoreCase(s)) {
			return null;
		}
		return s;
	}

	private static Integer asInt(Object raw) {
		if (raw == null) {
			return null;
		}
		if (raw instanceof Number n) {
			return n.intValue();
		}
		try {
			return Integer.parseInt(String.valueOf(raw).trim());
		} catch (NumberFormatException e) {
			return null;
		}
	}

	private static Long asLong(Object raw) {
		if (raw == null) {
			return null;
		}
		if (raw instanceof Number n) {
			return n.longValue();
		}
		try {
			return Long.parseLong(String.valueOf(raw).trim());
		} catch (NumberFormatException e) {
			return null;
		}
	}
}
