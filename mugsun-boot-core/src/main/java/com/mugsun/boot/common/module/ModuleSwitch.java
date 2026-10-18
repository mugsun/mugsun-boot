package com.mugsun.boot.common.module;

import java.util.Locale;
import java.util.Set;

/**
 * 可选模块开关的唯一取值解析器。yml / 环境变量（{@code mugsun.<f>.enabled}）、系统参数（{@code <f>.module.enabled}）
 * 保存校验与运行时判定都走这里，规则只写一遍：
 * <ul>
 *   <li>先去掉首尾空格；</li>
 *   <li>{@code true} / {@code false}（不区分大小写）：开 / 关；</li>
 *   <li>{@code 1} / {@code 0}：仍按开 / 关处理，但已弃用，会打警告；</li>
 *   <li>未设置或空值：视为没配，按默认（开）；</li>
 *   <li>其它值：非法。启动期（yml / 环境变量）直接启动失败；保存系统参数时拒收；运行时按关闭处理并限频打警告。</li>
 * </ul>
 */
public final class ModuleSwitch {

	/** 受本解析器约束的系统参数键 */
	public static final String PARAM_GIS = "gis.module.enabled";
	public static final String PARAM_AI = "ai.module.enabled";
	public static final Set<String> PARAM_KEYS = Set.of(PARAM_GIS, PARAM_AI);

	/** 解析结果 */
	public enum State {
		/** 未设置或空值：按默认开 */
		UNSET(true, true, false),
		ON(true, true, false),
		OFF(false, true, false),
		/** {@code 1}：开，已弃用 */
		LEGACY_ON(true, true, true),
		/** {@code 0}：关，已弃用 */
		LEGACY_OFF(false, true, true),
		/** 其它值 */
		INVALID(false, false, false);

		private final boolean enabled;
		private final boolean legal;
		private final boolean deprecated;

		State(boolean enabled, boolean legal, boolean deprecated) {
			this.enabled = enabled;
			this.legal = legal;
			this.deprecated = deprecated;
		}

		/** 模块是否开启（非法值按关闭） */
		public boolean enabled() {
			return enabled;
		}

		/** 是否合法取值（含已弃用的 1/0） */
		public boolean legal() {
			return legal;
		}

		/** 是否为已弃用写法（1/0） */
		public boolean deprecated() {
			return deprecated;
		}
	}

	public static final String MSG_INVALID = "模块开关只能填 true 或 false（不区分大小写），留空表示按默认开启";
	public static final String MSG_DEPRECATED = "模块开关写 1/0 已弃用，请改成 true/false";

	public static State parse(String raw) {
		if (raw == null) {
			return State.UNSET;
		}
		String v = raw.strip().toLowerCase(Locale.ROOT);
		return switch (v) {
			case "" -> State.UNSET;
			case "true" -> State.ON;
			case "false" -> State.OFF;
			case "1" -> State.LEGACY_ON;
			case "0" -> State.LEGACY_OFF;
			default -> State.INVALID;
		};
	}

	/**
	 * 保存系统参数前的校验入口：不是模块开关键返回 {@code null}；是则返回解析结果，调用方对 {@link State#legal()} 为假的值拒收。
	 */
	public static State checkParam(String paramKey, String value) {
		if (paramKey == null || !PARAM_KEYS.contains(paramKey.strip())) {
			return null;
		}
		return parse(value);
	}

	/** 日志里展示原值：截断并去掉换行，防日志注入 */
	static String quote(String raw) {
		if (raw == null) {
			return "null";
		}
		String s = raw.length() > 64 ? raw.substring(0, 64) + "…" : raw;
		return "'" + s.replaceAll("[\\r\\n\\t]", "?") + "'";
	}

	private ModuleSwitch() {
	}
}
