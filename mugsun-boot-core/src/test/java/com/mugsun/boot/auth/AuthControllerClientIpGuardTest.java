package com.mugsun.boot.auth;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 守卫：AuthController 记录的登录 IP（终端扩展数据、锁定键）必须走 {@code ClientIpResolver.clientIp}，
 * 不得直接用 {@code getRemoteAddr()}，否则反代后全记成代理地址，且与登录日志、锁定口径不一致。
 */
class AuthControllerClientIpGuardTest {

	@Test
	void authControllerUsesClientIpResolver() throws Exception {
		String code = Files.readString(Path.of("src/main/java/com/mugsun/boot/auth/AuthController.java"));
		assertThat(code).doesNotContain("getRemoteAddr(");
		assertThat(code).contains("ClientIpResolver.clientIp(request)");
	}
}
