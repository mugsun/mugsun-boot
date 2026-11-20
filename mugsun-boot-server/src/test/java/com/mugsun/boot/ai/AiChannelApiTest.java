package com.mugsun.boot.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.mugsun.boot.AbstractIntegrationTest;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * BOOT-093：渠道的 Webhook 地址里带着群机器人的 key，接口只返回打码后的样子；编辑时留空表示不改。
 * 地址用的是公网 IP 字面量，保存时不用解析域名；这里不真的往外发消息。
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class AiChannelApiTest extends AbstractIntegrationTest {

	private static final String TS = String.valueOf(System.currentTimeMillis());
	private static final String ALIAS = "it-ch-" + TS;
	private static final String KEY = "it-key-" + TS + "-0123456789ab";
	private static final String ADDRESS = "https://93.184.216.34/cgi-bin/webhook/send?key=" + KEY;
	private static final String SHOWN = "https://93.184.216.34/****89ab";

	private String adminToken;
	private long channelId;

	@BeforeAll
	void setup() {
		adminToken = loginAdmin();
		JsonNode created = save(null, "wecom", ADDRESS, "it-app", 1);
		assertThat(created.path("code").asInt()).as("建渠道：" + created.path("msg").asText()).isEqualTo(200);
		channelId = created.path("data").path("id").asLong();
		assertThat(channelId).isPositive();
		assertThat(created.path("data").path("paramsSample").asText()).as("保存结果里的地址").isEqualTo(SHOWN);
	}

	@AfterAll
	void cleanup() {
		if (channelId > 0) {
			post("/system/ai/channel/remove?ids=" + channelId, null, adminToken);
		}
	}

	@Test
	@DisplayName("列表只返回打码后的地址，库里存的是完整地址")
	void listShowsTheMaskedAddress() {
		JsonNode page = readBody(get("/system/ai/channel/page?pageNum=1&pageSize=20&alias=" + ALIAS, adminToken));

		assertThat(page.path("code").asInt()).as("列表：" + page.path("msg").asText()).isEqualTo(200);
		assertThat(page.toString()).as("列表返回里不能有 key").doesNotContain(KEY);
		assertThat(page.path("data").path("records").get(0).path("paramsSample").asText()).isEqualTo(SHOWN);
		assertThat(storedAddress()).isEqualTo(ADDRESS);
	}

	@Test
	@DisplayName("编辑时地址留空不改地址，绑定应用能清空；把打码后的地址交回来会被拒绝；填新地址才会换")
	void editingKeepsTheAddressUnlessANewOneIsGiven() {
		JsonNode kept = save(channelId, "wecom", "", "", 1);
		assertThat(kept.path("code").asInt()).as("留空保存：" + kept.path("msg").asText()).isEqualTo(200);
		assertThat(kept.path("data").path("paramsSample").asText()).isEqualTo(SHOWN);
		assertThat(storedAddress()).as("留空不改地址").isEqualTo(ADDRESS);
		String template = jdbc.queryForObject("select template_code from ai_channel_bind where id = ?", String.class, channelId);
		assertThat(template == null ? "" : template).as("绑定应用清空了").isEmpty();

		JsonNode masked = save(channelId, "wecom", SHOWN, "", 1);
		assertThat(masked.path("code").asInt()).as("交回打码地址：" + masked).isNotEqualTo(200);
		assertThat(masked.path("msg").asText()).contains("打码后的地址");
		assertThat(storedAddress()).as("被拒绝后地址没变").isEqualTo(ADDRESS);

		String replaced = "https://93.184.216.34/cgi-bin/webhook/send?key=it-new-" + TS + "-fedcba987654";
		JsonNode changed = save(channelId, "wecom", replaced, "", 0);
		assertThat(changed.path("code").asInt()).as("换地址：" + changed.path("msg").asText()).isEqualTo(200);
		assertThat(changed.path("data").path("paramsSample").asText()).isEqualTo("https://93.184.216.34/****7654");
		assertThat(storedAddress()).isEqualTo(replaced);

		// 渠道已经停用：调试不向外发，直接说明原因
		JsonNode debug = readBody(post("/system/ai/channel/debug?id=" + channelId, null, adminToken));
		assertThat(debug.path("code").asInt()).as("停用后调试：" + debug).isNotEqualTo(200);
		assertThat(debug.path("msg").asText()).isEqualTo("这个渠道已停用，没有发送。请先启用");
		assertThat(debug.toString()).doesNotContain("it-new-");

		JsonNode restored = save(channelId, "wecom", ADDRESS, "it-app", 1);
		assertThat(restored.path("code").asInt()).as("改回原地址：" + restored.path("msg").asText()).isEqualTo(200);
	}

	private String storedAddress() {
		return jdbc.queryForObject("select params_sample from ai_channel_bind where id = ?", String.class, channelId);
	}

	private JsonNode save(Long id, String code, String address, String template, int status) {
		Map<String, Object> body = new HashMap<>();
		if (id != null) {
			body.put("id", id);
		}
		body.put("alias", ALIAS);
		body.put("channelCode", code);
		body.put("paramsSample", address);
		body.put("templateCode", template);
		body.put("status", status);
		return readBody(post("/system/ai/channel/submit", body, adminToken));
	}
}
