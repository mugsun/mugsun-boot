package com.mugsun.boot.common.module;

import java.util.Locale;
import java.util.Set;

/**
 * 可选模块开关的唯一取值解析器，两层规则都在这里：
 * <p><b>yml / 环境变量</b>（{@code mugsun.<f>.enabled}，{@link #parseStrict}）——严格：
 * <ul>
 *   <li>属性不存在：按默认开启；</li>
 *   <li>只认恰好 {@code true} / {@code false}（不区分大小写，不能带首尾空格）；</li>
 *   <li>其它一律非法，包括设置了但为空、{@code 1}/{@code 0}、带空格的值、{@code yes}/{@code on} 等，启动失败。绝不悄悄开启或关闭模块。</li>
 * </ul>
 * <p><b>系统参数</b>（{@code <f>.module.enabled}，{@link #parse}）——宽松：
 * <ul>
 *   <li>先去掉首尾空格；{@code true} / {@code false} 不区分大小写；</li>
 *   <li>{@code 1} / {@code 0}：仍按开 / 关处理，但已弃用，会打警告；</li>
 *   <li>空值：按默认开启；</li>
 *   <li>其它值：保存时拒收；运行时按关闭处理并限频打警告。</li>
 * </ul>
 * 两层都接受的值，开关结论必然相同。
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

	/**
	 * yml / 环境变量层的严格解析：{@code null}（属性不存在）为 {@link State#UNSET}；恰好 true/false（不区分大小写）为开 / 关；
	 * 其它（含空串、1/0、带空格）一律 {@link State#INVALID}。
	 */
	public static State parseStrict(String raw) {
		if (raw == null) {
			return State.UNSET;
		}
		if ("true".equalsIgnoreCase(raw)) {
			return State.ON;
		}
		if ("false".equalsIgnoreCase(raw)) {
			return State.OFF;
		}
		return State.INVALID;
	}

	/** 属性名对应的环境变量名，如 {@code mugsun.ai.enabled} → {@code MUGSUN_AI_ENABLED} */
	public static String envName(String property) {
		return property.replace('.', '_').replace('-', '_').toUpperCase(Locale.ROOT);
	}

	/** yml / 环境变量层非法值的启动报错文案 */
	public static String strictError(String property, String raw) {
		return "配置项 " + property + "（环境变量 " + envName(property) + "）的值 " + quote(raw) + " 不合法。"
			+ "请改成 true 或 false（不区分大小写，前后不能有空格），或者删掉这个变量，按默认开启。"
			+ "空值、1、0、带首尾空格的值以及 yes/on/no/off 都必须改成 true 或 false";
	}

	/** 系统参数层的宽松解析（见类注释） */
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

	/** 原值最多展示多少个字符（码点），超出截断并加省略号 */
	static final int QUOTE_MAX = 64;

	/**
	 * 报错与日志里展示原值：先按码点截断，再把所有控制字符转义成「反斜杠 + u + 四位十六进制」，防日志注入 / 伪造日志行。
	 * <p>转义范围：C0（U+0000–U+001F，含 CR、LF、TAB）、DEL（U+007F）、C1（U+0080–U+009F），
	 * 以及会改变显示的格式字符（如 U+200B 零宽空格、U+202E 从右到左覆盖、U+2028 / U+2029 行段分隔符）。
	 * 反斜杠本身也写成两个反斜杠，原值里手写的「反斜杠 u000A」与真实换行在日志里不会混淆。
	 */
	public static String quote(String raw) {
		if (raw == null) {
			return "null";
		}
		boolean cut = raw.codePointCount(0, raw.length()) > QUOTE_MAX;
		String s = cut ? raw.substring(0, raw.offsetByCodePoints(0, QUOTE_MAX)) : raw;
		StringBuilder sb = new StringBuilder(s.length() + 8).append('\'');
		s.codePoints().forEach(cp -> {
			if (cp == '\\') {
				sb.append("\\\\");
			} else if (isUnsafe(cp)) {
				sb.append(String.format(Locale.ROOT, "\\u%04X", cp));
			} else {
				sb.appendCodePoint(cp);
			}
		});
		if (cut) {
			sb.append('…');
		}
		return sb.append('\'').toString();
	}

	private static boolean isUnsafe(int cp) {
		if (Character.isISOControl(cp)) {
			return true;
		}
		int type = Character.getType(cp);
		return type == Character.FORMAT || type == Character.LINE_SEPARATOR || type == Character.PARAGRAPH_SEPARATOR;
	}

	private ModuleSwitch() {
	}
}
