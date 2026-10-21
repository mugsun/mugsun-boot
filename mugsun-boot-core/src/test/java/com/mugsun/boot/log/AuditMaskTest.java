package com.mugsun.boot.log;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** 操作日志脱敏：AI 应用导入的整份 body（含字符串形态的 dsl）里不能留下 apiKey、secret 明文 */
class AuditMaskTest {

	private final ObjectMapper om = new ObjectMapper();

	@Test
	void aiAppImportBodyMasksKeysInNestedAndStringDsl() {
		Map<String, Object> body = Map.of(
			"name", "demo",
			"appType", "chat",
			"config", Map.of("apiKey", "sk-plain-111", "secret", "sec-plain-222"),
			"dsl", "{\"model\":{\"api_key\":\"sk-plain-333\",\"appSecret\":\"sec-plain-444\"},\"note\":\"ok\"}");
		String logged = AuditMask.maskAndTruncate(om, new Object[]{body});
		assertThat(logged).doesNotContain("sk-plain-111", "sec-plain-222", "sk-plain-333", "sec-plain-444");
		assertThat(logged).contains("demo", "ok");
	}

	@Test
	void escapedValueWithInnerEscapesIsFullyMasked() {
		String dsl = "{\"apiKey\":\"a\\\\b-sk-plain-555\"}";
		String logged = AuditMask.maskAndTruncate(om, new Object[]{Map.of("dsl", dsl)});
		assertThat(logged).doesNotContain("sk-plain-555");
	}
}
