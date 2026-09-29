package com.mugsun.boot.gen;

import java.util.Map;
import java.util.Optional;

/**
 * AI 模块可选增强：将自然语言转为规则 draft 可消费的结构化描述。
 * <p>core 不依赖 ai 模块；有 jar 时由 ai 侧注册实现，缺席则 {@link AiModelService} 走原规则解析。
 */
public interface GenAiDraftEnhancer {

	/**
	 * @param naturalLanguage 用户自然语言
	 * @return 若增强成功返回规则解析器可解析的英文标识符描述；空表示回落规则解析原文
	 */
	Optional<String> enhanceDescription(String naturalLanguage);

	/**
	 * 直接产出候选元数据（与 {@link AiModelService#draft} 同结构）；空则回落规则解析。
	 */
	default Optional<Map<String, Object>> draftCandidate(String naturalLanguage) {
		return Optional.empty();
	}
}
