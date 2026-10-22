package com.mugsun.boot.common.crypto;

import com.mugsun.core.web.crypto.ApiCryptoService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.env.MockEnvironment;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class PcEnvLocalWriterTest {

	/** 每次运行随机生成，源码里不放密钥字面量 */
	private static final String KEY = java.util.HexFormat.of().formatHex(java.security.SecureRandom.getSeed(16));

	@TempDir
	Path pc;

	private void pcRepo(String gitignore) throws Exception {
		Files.writeString(pc.resolve("package.json"), "{}");
		if (gitignore != null) {
			Files.writeString(pc.resolve(".gitignore"), gitignore);
		}
	}

	private PcEnvLocalWriter writer(String active) {
		ApiCryptoService s = mock(ApiCryptoService.class);
		when(s.transportKeyHex()).thenReturn(KEY);
		MockEnvironment env = new MockEnvironment().withProperty("mugsun.crypto.pc-env-local.pc-dir", pc.toString());
		if (active != null) {
			env.setProperty("spring.profiles.active", active);
		}
		return new PcEnvLocalWriter(s, env);
	}

	@Test
	@DisplayName("dev 且 .gitignore 有 *.local：写入 VITE_CRYPTO_API_KEY，保留其他行，再写一次不重复")
	void writesInDev() throws Exception {
		pcRepo("node_modules\n*.local\n");
		Files.writeString(pc.resolve(".env.local"), "VITE_OTHER=1\nVITE_CRYPTO_API_KEY=old\n");
		writer("dev").onReady();
		writer("dev").onReady();
		String s = Files.readString(pc.resolve(".env.local"));
		assertThat(s).contains("VITE_OTHER=1").contains("VITE_CRYPTO_API_KEY=" + KEY).doesNotContain("=old");
		assertThat(s.split("VITE_CRYPTO_API_KEY=", -1)).hasSize(2);
	}

	@Test
	@DisplayName("prod、active 为空、dev,prod：都不写")
	void notInProd() throws Exception {
		pcRepo("*.local\n");
		for (String active : new String[]{"prod", null, "", "DEV,prod", "default"}) {
			writer(active).onReady();
			assertThat(pc.resolve(".env.local")).as("active=%s", active).doesNotExist();
		}
	}

	@Test
	@DisplayName(".gitignore 没忽略 .env.local：拒绝写入")
	void refusesWhenNotIgnored() throws Exception {
		pcRepo("node_modules\n.env\n");
		writer("local").onReady();
		assertThat(pc.resolve(".env.local")).doesNotExist();
		Files.delete(pc.resolve(".gitignore"));
		writer("local").onReady();
		assertThat(pc.resolve(".env.local")).doesNotExist();
	}

	@Test
	@DisplayName("开关关闭：不写")
	void disabled() throws Exception {
		pcRepo("*.local\n");
		MockEnvironment env = new MockEnvironment().withProperty("mugsun.crypto.pc-env-local.pc-dir", pc.toString())
			.withProperty("spring.profiles.active", "dev").withProperty("mugsun.crypto.pc-env-local.enabled", "false");
		ApiCryptoService s = mock(ApiCryptoService.class);
		new PcEnvLocalWriter(s, env).onReady();
		assertThat(pc.resolve(".env.local")).doesNotExist();
	}
}
