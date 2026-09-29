package com.mugsun.boot.ai.support;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 检索融合：RRF（Reciprocal Rank Fusion），对齐 MaxKB/业界混合检索常见做法。
 */
public final class AiRrfFusion {

	private static final int RRF_K = 60;

	private AiRrfFusion() {
	}

	/**
	 * @param rankedLists 各路检索结果，已按相关性降序；元素需含 segmentId
	 * @param topK        最终条数
	 */
	@SafeVarargs
	public static List<Map<String, Object>> fuse(int topK, List<Map<String, Object>>... rankedLists) {
		Map<Long, Acc> acc = new LinkedHashMap<>();
		if (rankedLists != null) {
			for (List<Map<String, Object>> list : rankedLists) {
				if (list == null) {
					continue;
				}
				int rank = 0;
				for (Map<String, Object> hit : list) {
					Long id = toLong(hit.get("segmentId"));
					if (id == null) {
						continue;
					}
					rank++;
					Acc a = acc.computeIfAbsent(id, k -> new Acc(hit));
					a.rrf += 1.0 / (RRF_K + rank);
					Object sc = hit.get("score");
					if (sc instanceof Number n) {
						a.bestRaw = Math.max(a.bestRaw, n.doubleValue());
					}
					String src = hit.get("source") == null ? "" : hit.get("source").toString();
					if (!src.isBlank()) {
						a.sources.add(src);
					}
				}
			}
		}
		List<Map<String, Object>> out = new ArrayList<>();
		acc.values().stream()
			.sorted(Comparator.comparingDouble((Acc a) -> a.rrf).reversed())
			.limit(Math.max(1, topK))
			.forEach(a -> {
				Map<String, Object> m = new HashMap<>(a.sample);
				m.put("score", round(a.rrf));
				m.put("rawScore", round(a.bestRaw));
				m.put("source", String.join("+", a.sources));
				out.add(m);
			});
		return out;
	}

	/** 轻量「重排」：向量分与关键词命中率加权（无外部 rerank 模型时） */
	public static List<Map<String, Object>> lexicalBoost(List<Map<String, Object>> hits, String query, int topK) {
		if (hits == null || hits.isEmpty() || query == null || query.isBlank()) {
			return hits == null ? List.of() : hits;
		}
		String q = query.toLowerCase(Locale.ROOT);
		List<String> tokens = new ArrayList<>();
		for (String w : q.split("[^a-z0-9_\u4e00-\u9fff]+")) {
			if (w.length() >= 2) {
				tokens.add(w);
			}
			if (w.length() > 4 && w.matches("[\\u4e00-\\u9fff]+")) {
				for (int i = 0; i + 2 <= w.length(); i++) {
					tokens.add(w.substring(i, i + 2));
				}
			}
		}
		if (tokens.isEmpty()) {
			tokens.add(q.trim());
		}
		List<Map<String, Object>> scored = new ArrayList<>();
		for (Map<String, Object> h : hits) {
			String content = h.get("content") == null ? "" : h.get("content").toString().toLowerCase(Locale.ROOT);
			double base = h.get("score") instanceof Number n ? n.doubleValue() : 0;
			int hitTok = 0;
			for (String t : tokens) {
				if (t.length() >= 2 && content.contains(t)) {
					hitTok++;
				}
			}
			double boost = tokens.isEmpty() ? 0 : (double) hitTok / tokens.size();
			Map<String, Object> m = new HashMap<>(h);
			m.put("score", round(base * 0.65 + boost * 0.35));
			m.put("source", (h.get("source") == null ? "vector" : h.get("source")) + "+boost");
			scored.add(m);
		}
		scored.sort(Comparator.comparingDouble((Map<String, Object> m) ->
			m.get("score") instanceof Number n ? n.doubleValue() : 0).reversed());
		return new ArrayList<>(scored.subList(0, Math.min(topK, scored.size())));
	}

	private static Long toLong(Object o) {
		if (o instanceof Number n) {
			return n.longValue();
		}
		if (o == null) {
			return null;
		}
		try {
			return Long.valueOf(o.toString());
		} catch (Exception e) {
			return null;
		}
	}

	private static double round(double v) {
		return Math.round(v * 1_000_000d) / 1_000_000d;
	}

	private static final class Acc {
		final Map<String, Object> sample;
		final java.util.LinkedHashSet<String> sources = new java.util.LinkedHashSet<>();
		double rrf;
		double bestRaw;

		Acc(Map<String, Object> sample) {
			this.sample = new HashMap<>(sample);
		}
	}
}
