package com.mugsun.boot.track;

import com.mugsun.boot.common.constant.TrackConstants;
import com.mugsun.boot.common.module.TrackAskIntent;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 规则意图解析：无 LLM 或 LLM 失败时的确定性降级。只产出 intent/params，不碰数字。
 */
@Component
public class TrackAskRuleIntentParser {

	private static final Pattern DAYS = Pattern.compile("(?:近|最近|过去)?\\s*(\\d{1,2})\\s*天");
	private static final Pattern HOURS = Pattern.compile("(\\d{1,3})\\s*小时");
	private static final Pattern EVENT = Pattern.compile(
		"(?:事件|event)\\s*[「\"']?([A-Za-z][A-Za-z0-9_]{0,63}|\\$[A-Za-z0-9_]{1,62})[」\"']?");
	private static final Pattern STEPS = Pattern.compile(
		"(?:漏斗|步骤|转化)\\s*[：:]?\\s*([A-Za-z$][A-Za-z0-9_,\\s]{3,200})");

	public TrackAskIntent parse(String question, Integer daysHint) {
		String q = question == null ? "" : question.trim();
		if (q.isEmpty()) {
			return TrackAskIntent.unsupported("请用中文说明想看的指标，例如「最近 7 天概览」");
		}
		Integer days = daysHint != null ? daysHint : extractDays(q);
		Map<String, Object> params = baseParams(days);

		if (containsAny(q, "漏斗", "转化漏斗", "转化步骤")) {
			params.put("steps", extractSteps(q));
			Long wh = extractWindowHours(q);
			if (wh != null) {
				params.put("windowHours", wh);
			}
			return new TrackAskIntent("funnel", params, null);
		}
		if (containsAny(q, "留存", "次日留存", "七日留存", "回访")) {
			return new TrackAskIntent("retention", params, null);
		}
		if (containsAny(q, "性能", "加载", "卡顿", "vitals", "Vitals", "Web Vitals", "LCP", "INP", "CLS", "FCP", "TTFB")) {
			return new TrackAskIntent("vitals", params, null);
		}
		if (containsAny(q, "错误", "报错", "异常", "崩溃", "fingerprint")) {
			return new TrackAskIntent("errors", params, null);
		}
		if (containsAny(q, "在线", "此刻多少人", "当前在线")) {
			return new TrackAskIntent("online", params, null);
		}
		if (containsAny(q, "地域", "地理", "省份", "热力", "地图")) {
			return new TrackAskIntent("geo", params, null);
		}
		if (containsAny(q, "页面排行", "热门页面", "top 页面", "Top 页面", "页面访问", "页面榜")) {
			return new TrackAskIntent("pages", params, null);
		}
		if (containsAny(q, "事件分析", "事件列表", "事件统计", "自定义事件") || EVENT.matcher(q).find()) {
			String eventName = extractEvent(q);
			if (eventName != null) {
				params.put("eventName", eventName);
			}
			return new TrackAskIntent("events", params, null);
		}
		if (containsAny(q, "趋势", "曲线", "走势", "按天", "按日", "时间序列")) {
			params.put("dimType", TrackConstants.DIM_OVERVIEW);
			return new TrackAskIntent("trend", params, null);
		}
		if (containsAny(q, "概览", "总览", "pv", "PV", "uv", "UV", "访问量", "多少人", "会话数", "跳出")) {
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
		if (q.contains("今天") || q.contains("今日")) {
			return 1;
		}
		if (q.contains("本周") || q.contains("一周") || q.contains("7 天") || q.contains("七天")) {
			return 7;
		}
		if (q.contains("一个月") || q.contains("30 天") || q.contains("三十天")) {
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
		if (q.contains("1 小时") || q.contains("一小时")) {
			return 1L;
		}
		if (q.contains("7 天窗") || q.contains("一周窗") || q.contains("168")) {
			return 168L;
		}
		return null;
	}

	private static String extractEvent(String q) {
		Matcher m = EVENT.matcher(q);
		if (m.find()) {
			return m.group(1);
		}
		return null;
	}

	private static String extractSteps(String q) {
		Matcher m = STEPS.matcher(q);
		if (m.find()) {
			String raw = m.group(1).replace('，', ',').replace(' ', ',');
			List<String> parts = new ArrayList<>();
			for (String p : raw.split(",")) {
				String s = p.trim();
				if (!s.isEmpty()) {
					parts.add(s);
				}
			}
			if (parts.size() >= 2) {
				return String.join(",", parts);
			}
		}
		return null;
	}

	private static boolean containsAny(String q, String... keys) {
		for (String k : keys) {
			if (q.contains(k)) {
				return true;
			}
		}
		return false;
	}
}
