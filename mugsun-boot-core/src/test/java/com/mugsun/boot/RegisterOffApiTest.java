package com.mugsun.boot;

import com.fasterxml.jackson.databind.JsonNode;
import com.mugsun.boot.auth.AuthController;
import com.mugsun.boot.auth.register.RegisterGate;
import com.mugsun.boot.captcha.CaptchaController;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 注册关闭（生产默认）：注册接口 404，公开配置 registerMode=OFF。
 * 复用基础上下文，只把两处控制器里的 RegisterGate 换成 OFF 实例；
 * 另起一个 Spring 上下文会撞 warm-flow 的重复 Bean，并污染后续测试。
 */
class RegisterOffApiTest extends AbstractIntegrationTest {

	@Autowired AuthController auth;
	@Autowired CaptchaController captcha;
	@Autowired RegisterGate original;
	@Autowired StringRedisTemplate redis;

	@BeforeEach
	void switchOff() {
		RegisterGate off = new RegisterGate(
				new MockEnvironment().withProperty("mugsun.auth.register.enabled", "false"), redis);
		ReflectionTestUtils.setField(auth, "registerGate", off);
		ReflectionTestUtils.setField(captcha, "registerGate", off);
	}

	@AfterEach
	void restore() {
		ReflectionTestUtils.setField(auth, "registerGate", original);
		ReflectionTestUtils.setField(captcha, "registerGate", original);
	}

	@Test
	void registerIs404WhenOff() {
		int status = post("/auth/register", Map.of("username", "it_off_x", "password", "x"), null)
				.getStatusCode().value();
		assertThat(status).isEqualTo(404);
	}

	@Test
	void publicConfigSaysOff() {
		JsonNode r = readBody(get("/auth/captcha/config", null));
		assertThat(r.path("data").path("registerMode").asText()).isEqualTo("OFF");
	}
}
