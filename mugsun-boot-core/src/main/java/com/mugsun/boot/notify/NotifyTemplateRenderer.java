package com.mugsun.boot.notify;

import com.mugsun.core.tool.exception.ServiceException;
import org.springframework.stereotype.Component;

import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * 统一模板渲染器：保存期抽取 ${key} 占位落库，发送期缺参 fail-fast（业务异常）。
 * <p>站内信（原静默清除未提供占位）与邮件（原不清除）两个渲染点统一收编到此——
 * 方向取 fail-fast：静默清除会把未提供的 ${key} 吞掉发出残缺通知且运维不可见；
 * 内置调用方（login_2fa / welcome）参数恒齐全，不受收编影响。
 */
@Component
public class NotifyTemplateRenderer {

	/** ${key} 占位（key 非空且不含 }） */
	private static final Pattern PLACEHOLDER = Pattern.compile("\\$\\{([^}]+)}");

	/** 抽取文本中的全部占位参数名（保存期落 required_params 用） */
	public Set<String> extractParams(String... texts) {
		Set<String> keys = new LinkedHashSet<>();
		if (texts == null) {
			return keys;
		}
		for (String text : texts) {
			if (text == null) {
				continue;
			}
			Matcher matcher = PLACEHOLDER.matcher(text);
			while (matcher.find()) {
				keys.add(matcher.group(1));
			}
		}
		return keys;
	}

	/** 渲染：参数齐全则替换全部占位；缺参立即抛业务异常（fail-fast，不发残缺通知）。值为 null 按空串处理 */
	public String render(String template, Map<String, String> params) {
		if (template == null) {
			return null;
		}
		Set<String> required = extractParams(template);
		Set<String> missing = required.stream()
			.filter(key -> params == null || !params.containsKey(key))
			.collect(Collectors.toCollection(LinkedHashSet::new));
		if (!missing.isEmpty()) {
			throw new ServiceException("通知模板缺少参数: " + String.join(",", missing));
		}
		String result = template;
		for (String key : required) {
			String value = params.get(key);
			result = result.replace("${" + key + "}", value == null ? "" : value);
		}
		return result;
	}
}
