package com.mugsun.boot.common.module;

/**
 * 可选：用 LLM 把中文提问抽成结构化意图 JSON。
 * <p>AI 模块装配时提供实现；未装配或调用失败时由埋点问数据服务降级规则解析 / unsupported，
 * <b>禁止</b>用模型半截编造指标数字。
 */
public interface TrackAskLlmGateway {

	/**
	 * @param question 用户中文提问
	 * @param daysHint 请求里的 days（可空），可写入 params.days
	 * @return 解析成功的意图；无法解析时返回 {@link TrackAskIntent#unsupported}
	 * @throws RuntimeException 超时/调用失败等——调用方不得据此填数
	 */
	TrackAskIntent extract(String question, Integer daysHint);
}
