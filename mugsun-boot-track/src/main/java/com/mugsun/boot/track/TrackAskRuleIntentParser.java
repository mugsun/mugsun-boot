package com.mugsun.boot.track;

import com.mugsun.boot.common.constant.TrackConstants;
import com.mugsun.boot.common.module.TrackAskIntent;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 规则意图解析：无 LLM 或 LLM 失败时的确定性降级。只产出 intent/params，不碰数字。
 * <p>中文、英文的问法都认。中文关键词按「包含」判断；英文关键词按整词、不分大小写（map 不该命中 sitemap）。
 * 提示语里给用户的英文例句（overview of the last 7 days、funnel: view,submit,done）必须能在这里解析出来。
 */
@Component
public class TrackAskRuleIntentParser {

	/** 数字前面不能紧挨着字母、数字、下划线：事件名 view_24h、signup_7day 里的数字不是天数和小时数 */
	private static final Pattern DAYS = Pattern.compile("(?<![A-Za-z0-9_])(\\d{1,2})\\s*(?:天|-?days?\\b)",
		Pattern.CASE_INSENSITIVE);
	private static final Pattern HOURS = Pattern.compile("(?<![A-Za-z0-9_])(\\d{1,3})\\s*(?:小时|-?(?:hours?|hrs?|h)\\b)",
		Pattern.CASE_INSENSITIVE);
	private static final String EVENT_NAME = "([A-Za-z][A-Za-z0-9_]{0,63}|\\$[A-Za-z0-9_]{1,62})";
	private static final Pattern EVENT = Pattern.compile("事件\\s*[「\"']?" + EVENT_NAME + "[」\"']?");
	private static final Pattern EVENT_EN = Pattern.compile(
		"\\bevent\\b\\s*(?:named\\s+|called\\s+)?[「\"'`]?" + EVENT_NAME, Pattern.CASE_INSENSITIVE);
	/** 英文里跟在 event 后面、但不是事件名的词（event analysis、event list、event in the last 7 days…） */
	private static final Set<String> NOT_EVENT_NAMES = Set.of(
		"analysis", "analytics", "list", "lists", "stats", "statistics", "count", "counts", "trend", "trends",
		"overview", "summary", "name", "names", "type", "types", "data", "in", "for", "of", "by", "on", "over",
		"during", "from", "the", "a", "an", "and", "or", "is", "are", "was", "were", "with", "within", "per",
		"last", "past", "today", "this", "that", "named", "called");
	private static final Pattern STEPS = Pattern.compile(
		"(?:漏斗|步骤|转化)\\s*[：:]?\\s*([A-Za-z$][A-Za-z0-9_,\\s]{3,200})");
	/** 英文问句里步骤后面还会跟别的英文词，所以必须写冒号、步骤之间必须用逗号：funnel: view,submit,done */
	private static final Pattern STEPS_EN = Pattern.compile(
		"\\b(?:funnel|steps?)\\b\\s*[：:]\\s*([A-Za-z$][A-Za-z0-9_$]*(?:\\s*[,，]\\s*[A-Za-z$][A-Za-z0-9_$]*)+)",
		Pattern.CASE_INSENSITIVE);

	private static final Pattern FUNNEL_EN = words("funnels?", "conversion steps");
	private static final Pattern RETENTION_EN = words("retention", "retained", "returning users?", "come back", "came back");
	private static final Pattern VITALS_EN = words("performance", "load time", "loading", "slow", "web vitals", "vitals",
		"lcp", "inp", "cls", "fcp", "ttfb");
	private static final Pattern ERRORS_EN = words("errors?", "exceptions?", "crash", "crashes", "fingerprints?");
	private static final Pattern ONLINE_EN = words("online", "right now", "active now", "currently active");
	private static final Pattern GEO_EN = words("regions?", "geo", "geography", "provinces?", "countr(?:y|ies)",
		"cit(?:y|ies)", "heat ?map", "map", "locations?");
	private static final Pattern PAGES_EN = words("top pages?", "popular pages?", "hot pages?", "most visited",
		"page ranking", "pages? by views?");
	private static final Pattern EVENTS_EN = words("event analysis", "event list", "event stats", "event statistics",
		"custom events?", "events");
	private static final Pattern TREND_EN = words("trends?", "curve", "over time", "by day", "per day", "daily",
		"time series");
	private static final Pattern OVERVIEW_EN = words("overview", "summary", "page ?views?", "visitors?", "visits?",
		"sessions?", "bounce", "traffic", "how many (?:users|people)");

	private static final Pattern TODAY_EN = words("today");
	private static final Pattern WEEK_EN = words("(?:this|last|past|a|one) week", "weekly");
	private static final Pattern MONTH_EN = words("(?:this|last|past|a|one) month", "monthly");
	private static final Pattern ONE_HOUR_EN = words("(?:an|one) hour");
	private static final Pattern WEEK_WINDOW_EN = words("(?:7|seven)[- ]day window", "(?:1|one)[- ]week window");

	public TrackAskIntent parse(String question, Integer daysHint) {
		String q = question == null ? "" : question.trim();
		if (q.isEmpty()) {
			return TrackAskIntent.unsupported("请说明想看的指标，例如「最近 7 天概览」");
		}
		Integer days = daysHint != null ? daysHint : extractDays(q);
		Map<String, Object> params = baseParams(days);

		if (containsAny(q, "漏斗", "转化漏斗", "转化步骤") || FUNNEL_EN.matcher(q).find()) {
			params.put("steps", extractSteps(q));
			Long wh = extractWindowHours(q);
			if (wh != null) {
				params.put("windowHours", wh);
			}
			return new TrackAskIntent("funnel", params, null);
		}
		if (containsAny(q, "留存", "次日留存", "七日留存", "回访") || RETENTION_EN.matcher(q).find()) {
			return new TrackAskIntent("retention", params, null);
		}
		if (containsAny(q, "性能", "加载", "卡顿", "vitals", "Vitals", "Web Vitals", "LCP", "INP", "CLS", "FCP", "TTFB")
			|| VITALS_EN.matcher(q).find()) {
			return new TrackAskIntent("vitals", params, null);
		}
		if (containsAny(q, "错误", "报错", "异常", "崩溃", "fingerprint") || ERRORS_EN.matcher(q).find()) {
			return new TrackAskIntent("errors", params, null);
		}
		if (containsAny(q, "在线", "此刻多少人", "当前在线") || ONLINE_EN.matcher(q).find()) {
			return new TrackAskIntent("online", params, null);
		}
		if (containsAny(q, "地域", "地理", "省份", "热力", "地图") || GEO_EN.matcher(q).find()) {
			return new TrackAskIntent("geo", params, null);
		}
		if (containsAny(q, "页面排行", "热门页面", "top 页面", "Top 页面", "页面访问", "页面榜") || PAGES_EN.matcher(q).find()) {
			return new TrackAskIntent("pages", params, null);
		}
		String eventName = extractEvent(q);
		if (eventName != null || containsAny(q, "事件分析", "事件列表", "事件统计", "自定义事件")
			|| EVENTS_EN.matcher(q).find()) {
			if (eventName != null) {
				params.put("eventName", eventName);
			}
			return new TrackAskIntent("events", params, null);
		}
		if (containsAny(q, "趋势", "曲线", "走势", "按天", "按日", "时间序列") || TREND_EN.matcher(q).find()) {
			params.put("dimType", TrackConstants.DIM_OVERVIEW);
			return new TrackAskIntent("trend", params, null);
		}
		if (containsAny(q, "概览", "总览", "pv", "PV", "uv", "UV", "访问量", "多少人", "会话数", "跳出")
			|| OVERVIEW_EN.matcher(q).find()) {
			return new TrackAskIntent("overview", params, null);
		}
		return TrackAskIntent.unsupported(
			"暂时只支持问概览、趋势、页面、事件、在线、地域、性能、错误、漏斗或留存。请换一种说法，例如「最近 7 天概览」");
	}

	private static Map<String, Object> baseParams(Integer days) {
		Map<String, Object> params = new LinkedHashMap<>();
		params.put("days", days == null ? 7 : days);
		params.put("eventName", null);
		params.put("dimType", null);
		params.put("dimKey", null);
		params.put("steps", null);
		params.put("windowHours", null);
		params.put("fingerprint", null);
		params.put("routePath", null);
		params.put("limit", null);
		return params;
	}

	private static Integer extractDays(String q) {
		Matcher m = DAYS.matcher(q);
		if (m.find()) {
			int d = Integer.parseInt(m.group(1));
			if (d >= 1 && d <= TrackConstants.ANALYSIS_DAYS_MAX) {
				return d;
			}
		}
		if (q.contains("今天") || q.contains("今日") || TODAY_EN.matcher(q).find()) {
			return 1;
		}
		if (q.contains("本周") || q.contains("一周") || q.contains("7 天") || q.contains("七天") || WEEK_EN.matcher(q).find()) {
			return 7;
		}
		if (q.contains("一个月") || q.contains("30 天") || q.contains("三十天") || MONTH_EN.matcher(q).find()) {
			return 30;
		}
		return null;
	}

	private static Long extractWindowHours(String q) {
		Matcher m = HOURS.matcher(q);
		if (m.find()) {
			long h = Long.parseLong(m.group(1));
			if (TrackConstants.FUNNEL_WINDOW_OPTIONS_HOURS.contains(h)) {
				return h;
			}
		}
		if (q.contains("1 小时") || q.contains("一小时") || ONE_HOUR_EN.matcher(q).find()) {
			return 1L;
		}
		if (q.contains("7 天窗") || q.contains("一周窗") || q.contains("168") || WEEK_WINDOW_EN.matcher(q).find()) {
			return 168L;
		}
		return null;
	}

	private static String extractEvent(String q) {
		Matcher m = EVENT.matcher(q);
		if (m.find()) {
			return m.group(1);
		}
		Matcher en = EVENT_EN.matcher(q);
		while (en.find()) {
			if (!NOT_EVENT_NAMES.contains(en.group(1).toLowerCase(Locale.ROOT))) {
				return en.group(1);
			}
		}
		return null;
	}

	private static String extractSteps(String q) {
		String steps = steps(STEPS.matcher(q));
		return steps != null ? steps : steps(STEPS_EN.matcher(q));
	}

	private static String steps(Matcher m) {
		if (!m.find()) {
			return null;
		}
		String raw = m.group(1).replace('，', ',').replace(' ', ',');
		List<String> parts = new ArrayList<>();
		for (String p : raw.split(",")) {
			String s = p.trim();
			if (!s.isEmpty()) {
				parts.add(s);
			}
		}
		return parts.size() >= 2 ? String.join(",", parts) : null;
	}

	private static boolean containsAny(String q, String... keys) {
		for (String k : keys) {
			if (q.contains(k)) {
				return true;
			}
		}
		return false;
	}

	/** 英文关键词：整词匹配，不分大小写 */
	private static Pattern words(String... alternatives) {
		return Pattern.compile("\\b(?:" + String.join("|", alternatives) + ")\\b", Pattern.CASE_INSENSITIVE);
	}
}
