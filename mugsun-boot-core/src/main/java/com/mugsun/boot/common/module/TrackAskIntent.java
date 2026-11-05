package com.mugsun.boot.common.module;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 埋点问数据解析出的意图与参数。数字一律由分析服务回填，本对象不含指标。
 */
public final class TrackAskIntent {

	public static final String UNSUPPORTED = "unsupported";

	private final String intent;
	private final Map<String, Object> params;
	private final String warning;

	public TrackAskIntent(String intent, Map<String, Object> params, String warning) {
		this.intent = intent == null || intent.isBlank() ? UNSUPPORTED : intent.trim();
		this.params = params == null ? new LinkedHashMap<>() : new LinkedHashMap<>(params);
		this.warning = warning;
	}

	public static TrackAskIntent unsupported(String warning) {
		return new TrackAskIntent(UNSUPPORTED, Map.of(), warning);
	}

	public String intent() {
		return intent;
	}

	public Map<String, Object> params() {
		return params;
	}

	public String warning() {
		return warning;
	}

	public boolean unsupported() {
		return UNSUPPORTED.equals(intent);
	}
}
