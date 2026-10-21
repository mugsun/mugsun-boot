package com.mugsun.boot.common.crypto;

import com.mugsun.core.web.config.MugsunWebAutoConfiguration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.jackson.JacksonAutoConfiguration;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 证明 boot 用的是 core 0.2.0-SNAPSHOT 的接口加密：不配 api-key、也不开临时密钥就拒绝启动
 * （旧 0.1.0 会静默回落内置密钥，这条在 0.1.0 上会失败）。
 */
class ApiCryptoKeyStartupTest {

	private final WebApplicationContextRunner runner = new WebApplicationContextRunner()
		.withConfiguration(AutoConfigurations.of(JacksonAutoConfiguration.class, MugsunWebAutoConfiguration.class));

	@Test
	@DisplayName("不配 api-key、不开 allow-ephemeral-keys：启动失败，报错点名 MUGSUN_API_KEY")
	void missingKeyWithoutEphemeralFails() {
		runner.run(ctx -> {
			assertThat(ctx).hasFailed();
			Throwable t = ctx.getStartupFailure();
			while (t.getCause() != null) {
				t = t.getCause();
			}
			assertThat(t).isInstanceOf(IllegalStateException.class).hasMessageContaining("MUGSUN_API_KEY");
		});
	}

	@Test
	@DisplayName("开了 allow-ephemeral-keys：能启动")
	void ephemeralStarts() {
		runner.withPropertyValues("mugsun.crypto.allow-ephemeral-keys=true")
			.run(ctx -> assertThat(ctx).hasNotFailed());
	}
}
