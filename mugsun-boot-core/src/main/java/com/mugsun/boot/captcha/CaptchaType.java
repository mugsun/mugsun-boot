package com.mugsun.boot.captcha;

import java.util.Collection;
import java.util.EnumSet;
import java.util.Locale;
import java.util.Set;

/** 行为验证码题型。由服务端按租户启用集合随机选择，客户端不能指定。 */
public enum CaptchaType {
	SLIDER, ROTATE, WORD_CLICK, ICON_CLICK, JIGSAW;

	/** 键盘可操作的题型 */
	public static final Set<CaptchaType> KEYBOARD = EnumSet.of(SLIDER, ROTATE);

	public static CaptchaType parse(String v) {
		if (v == null || v.isBlank()) {
			return null;
		}
		try {
			return valueOf(v.trim().toUpperCase(Locale.ROOT));
		} catch (IllegalArgumentException e) {
			return null;
		}
	}

	/** 解析集合，忽略非法值；结果为空时返回 null */
	public static Set<CaptchaType> parseAll(Collection<String> values) {
		EnumSet<CaptchaType> out = EnumSet.noneOf(CaptchaType.class);
		if (values != null) {
			for (String v : values) {
				CaptchaType t = parse(v);
				if (t != null) {
					out.add(t);
				}
			}
		}
		return out.isEmpty() ? null : out;
	}

	/** 逗号分隔（存库格式） */
	public static Set<CaptchaType> parseCsv(String csv) {
		return csv == null ? null : parseAll(java.util.Arrays.asList(csv.split(",")));
	}

	public static String toCsv(Collection<CaptchaType> types) {
		return types.stream().sorted().map(Enum::name).collect(java.util.stream.Collectors.joining(","));
	}
}
