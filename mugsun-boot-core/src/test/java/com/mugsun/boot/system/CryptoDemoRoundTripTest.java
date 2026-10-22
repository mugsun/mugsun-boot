package com.mugsun.boot.system;

import com.fasterxml.jackson.databind.JsonNode;
import com.mugsun.boot.AbstractIntegrationTest;
import com.mugsun.core.web.crypto.ApiCryptoService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** 集成测试没配 api-key（自动生成到 mugsun.home）：CryptoDemo 加密请求 → 解密回声 → 加密响应，往返能通 */
class CryptoDemoRoundTripTest extends AbstractIntegrationTest {

	@Autowired
	ApiCryptoService crypto;

	@Test
	@DisplayName("自动生成密钥下 /system/crypto/echo 加解密往返")
	void echoRoundTrip() throws Exception {
		String token = loginAdmin();
		String req = crypto.encrypt(om.writeValueAsString(Map.of("text", "国密往返")));
		JsonNode body = readBody(post("/system/crypto/echo", Map.of("encryptData", req), token));
		assertThat(body.path("dataType").asText()).isEqualTo("ENCRYPT");
		JsonNode data = om.readTree(crypto.decrypt(body.path("data").asText()));
		assertThat(data.path("received").asText()).isEqualTo("国密往返");
	}
}
