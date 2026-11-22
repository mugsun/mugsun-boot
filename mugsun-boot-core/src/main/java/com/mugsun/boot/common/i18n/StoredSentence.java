package com.mugsun.boot.common.i18n;

import com.mugsun.core.i18n.message.I18n;

import java.util.Locale;

/**
 * 要存起来、过后给别人看的一句话（比如上报被拒时留给管理员看的样例）。写的人和看的人语言可能不一样，
 * 所以存的是原文和要填的值，看的时候再按看的人的语言拼出来。
 * <p>没有变量的句子存的就是原文本身，老数据不用迁移：{@link #render} 遇到不是 {@link #pack} 存的文字，按整句翻译。
 * <p>存的地方有长度上限时不要自己截断（值被截掉一半，读出来就是另一个数），用 {@link #fit}。
 */
public final class StoredSentence {

	/** 原文和各个值之间的分隔符（单元分隔符，正常文字里不会出现） */
	private static final char SEPARATOR = '\u001F';

	private StoredSentence() {
	}

	/** 原文里用 {0}、{1} 占位，值跟在后面；没有值时返回的就是原文 */
	public static String pack(String pattern, Object... args) {
		if (pattern == null || args == null || args.length == 0) {
			return pattern;
		}
		StringBuilder packed = new StringBuilder(pattern);
		for (Object arg : args) {
			packed.append(SEPARATOR).append(String.valueOf(arg).replace(SEPARATOR, ' '));
		}
		return packed.toString();
	}

	/**
	 * 存得下就原样返回；存不下的先按默认语言拼成一句话，再截短并以省略号结尾。
	 * 这样的一条以后不能再翻译，但不会把值截掉一半、读出来变成另一个数。
	 */
	public static String fit(String stored, int maxLength) {
		if (stored == null || stored.length() <= maxLength) {
			return stored;
		}
		String sentence = stored.indexOf(SEPARATOR) < 0 ? stored : render(stored, true);
		if (sentence.length() <= maxLength) {
			return sentence;
		}
		return maxLength < 1 ? "" : sentence.substring(0, maxLength - 1) + '…';
	}

	/** 按这次请求的语言拼出来；占位写坏了的照存下来的原文返回，不报错 */
	public static String render(String stored) {
		if (stored == null || stored.indexOf(SEPARATOR) < 0) {
			return I18n.translate(stored);
		}
		return render(stored, false);
	}

	private static String render(String stored, boolean defaultLanguage) {
		String[] parts = stored.split(String.valueOf(SEPARATOR), -1);
		Object[] args = new Object[parts.length - 1];
		for (int i = 1; i < parts.length; i++) {
			args[i - 1] = number(parts[i]);
		}
		return defaultLanguage ? I18n.get((Locale) null, parts[0], args) : I18n.get(parts[0], args);
	}

	/** 数字存进去再读出来还得是数字，英文才能按数量分单复数 */
	private static Object number(String text) {
		try {
			long value = Long.parseLong(text);
			return String.valueOf(value).equals(text) ? (Object) value : text;
		} catch (NumberFormatException e) {
			return text;
		}
	}
}
