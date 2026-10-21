package com.mugsun.boot.common.setting;

import com.mugsun.boot.common.module.ModuleSwitch;

import java.util.Collection;
import java.util.EnumSet;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 枚举型配置项（yml / 环境变量）的严格解析器，与模块开关 {@link ModuleSwitch#parseStrict} 同一套规则：
 * <ul>
 *   <li>只认枚举名本身（不区分大小写），其它值一律非法，启动失败，绝不悄悄按默认值处理；</li>
 *   <li>已移除的取值单独报「已移除」并写明改法（如验证码的 {@code IMAGE}）；</li>
 *   <li>报错里的原值经 {@link ModuleSwitch#quote} 截断并转义控制字符，防日志注入。</li>
 * </ul>
 * 失败抛 {@link InvalidSettingException}，由 {@link InvalidSettingFailureAnalyzer} 整理成启动失败说明。
 */
public final class StrictEnumSetting {

	/**
	 * 解析单个取值。{@code raw == null}（属性不存在）返回 {@code null}，由调用方按默认处理；
	 * 设置了但为空、带首尾空格、不是枚举名都抛 {@link InvalidSettingException}。
	 *
	 * @param removed 已移除的取值（大写）→ 改法说明；可为空
	 */
	public static <E extends Enum<E>> E parseOne(String property, String raw, Class<E> type, Map<String, String> removed) {
		if (raw == null) {
			return null;
		}
		return parseElement(property, raw, raw, type, removed);
	}

	/**
	 * 解析列表取值（yml 列表或逗号分隔）。{@code raw == null} 返回 {@code null}；空列表、空元素、未知值、已移除值都抛异常。
	 * 重复的取值合并。
	 */
	public static <E extends Enum<E>> EnumSet<E> parseList(String property, Collection<String> raw, Class<E> type,
															 Map<String, String> removed) {
		if (raw == null) {
			return null;
		}
		String whole = String.join(",", raw.stream().map(v -> v == null ? "" : v).toList());
		if (raw.isEmpty()) {
			throw new InvalidSettingException(property, whole, "不能为空，至少写一个取值。" + allowed(type));
		}
		EnumSet<E> out = EnumSet.noneOf(type);
		for (String v : raw) {
			out.add(parseElement(property, whole, v == null ? "" : v, type, removed));
		}
		return out;
	}

	private static <E extends Enum<E>> E parseElement(String property, String whole, String v, Class<E> type,
													  Map<String, String> removed) {
		if (v.isEmpty()) {
			throw new InvalidSettingException(property, whole, "含空值。" + allowed(type));
		}
		if (!v.equals(v.strip())) {
			throw new InvalidSettingException(property, whole,
				"取值 " + ModuleSwitch.quote(v) + " 前后不能有空格。" + allowed(type));
		}
		String upper = v.toUpperCase(Locale.ROOT);
		if (removed != null && removed.containsKey(upper)) {
			throw new InvalidSettingException(property, whole, "取值 " + upper + " 已移除。" + removed.get(upper));
		}
		for (E e : type.getEnumConstants()) {
			if (e.name().equals(upper)) {
				return e;
			}
		}
		throw new InvalidSettingException(property, whole, "取值 " + ModuleSwitch.quote(v) + " 不认识。" + allowed(type));
	}

	/** 「可选值：A、B、C（不区分大小写）」 */
	public static <E extends Enum<E>> String allowed(Class<E> type) {
		return "可选值：" + java.util.Arrays.stream(type.getEnumConstants()).map(Enum::name).collect(Collectors.joining("、"))
			+ "（不区分大小写，前后不能有空格）";
	}

	private StrictEnumSetting() {
	}
}
