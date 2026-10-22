package com.mugsun.boot.common.crypto;

import com.mugsun.core.web.config.MugsunWebAutoConfiguration;
import com.mugsun.core.web.crypto.ApiCryptoService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.jackson.JacksonAutoConfiguration;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 证明 boot 用的是开箱即用版 core：不配 api-key 也能启动（自动生成并持久化），
 * 生产 strict-keys=true 也不因缺 api-key 拒绝启动；格式非法仍拒绝。
 */
class ApiCryptoKeyStartupTest {

	@TempDir
	Path home;

	private WebApplicationContextRunner runner() {
		return new WebApplicationContextRunner()
			.withConfiguration(AutoConfigurations.of(JacksonAutoConfiguration.class, MugsunWebAutoConfiguration.class))
			.withPropertyValues("mugsun.home=" + home);
	}

	@Test
	@DisplayName("不配 api-key（含 strict-keys=true）：能启动，密钥文件落在 mugsun.home")
	void missingKeyStarts() {
		runner().withPropertyValues("mugsun.crypto.strict-keys=true").run(ctx -> {
			assertThat(ctx).hasNotFailed();
			ApiCryptoService s = ctx.getBean(ApiCryptoService.class);
			assertThat(s.keySource()).isEqualTo(ApiCryptoService.KeySource.AUTO_GENERATED);
			assertThat(s.localKeyFile()).isEqualTo(home.resolve(ApiCryptoService.LOCAL_KEY_FILE));
		});
	}

	@Test
	@DisplayName("api-key 格式非法：仍拒绝启动")
	void malformedKeyFails() {
		runner().withPropertyValues("mugsun.crypto.api-key=short").run(ctx -> {
			assertThat(ctx).hasFailed();
			Throwable t = ctx.getStartupFailure();
			while (t.getCause() != null) {
				t = t.getCause();
			}
			assertThat(t).isInstanceOf(IllegalStateException.class).hasMessageContaining("MUGSUN_API_KEY");
		});
	}
}
