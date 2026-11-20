package com.mugsun.boot.monitor;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** 访问日志脱敏：AI 渠道的 Webhook 地址里带着群机器人的 key，不能原样记进日志 */
class ApiParamMaskTest {

	private final ObjectMapper om = new ObjectMapper();

	@Test
	void aiChannelWebhookAddressIsMasked() {
		String logged = ApiParamMask.maskJson(om, "{\"alias\":\"发布通知群\",\"channelCode\":\"wecom\","
			+ "\"paramsSample\":\"https://qyapi.weixin.qq.com/cgi-bin/webhook/send?key=plain-key-666\"}");

		assertThat(logged).doesNotContain("plain-key-666", "qyapi.weixin.qq.com");
		assertThat(logged).contains("发布通知群", "wecom");
	}
}
