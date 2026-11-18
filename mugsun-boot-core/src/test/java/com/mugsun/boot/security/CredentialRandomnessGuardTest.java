package com.mugsun.boot.security;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 生成验证码、令牌、口令、密钥的类只能用 SecureRandom。
 * <p>java.util.Random 是 48 位线性同余，拿到两三个输出就能推出后续值；Hutool 的 fastSimpleUUID / fastUUID / RandomUtil.randomXxx
 * 底层是 ThreadLocalRandom，同样不是安全随机数。非凭据场景（traceId、分布式锁标识、采样）不在此列。
 */
class CredentialRandomnessGuardTest {

	private static final Pattern WEAK = Pattern.compile(
		"new Random\\(|import java\\.util\\.Random;|ThreadLocalRandom|RandomUtil\\.random|fastSimpleUUID|fastUUID|Math\\.random");

	@ParameterizedTest
	@ValueSource(strings = {
		"mugsun-boot-core/src/main/java/com/mugsun/boot/system/service/SmsService.java",
		"mugsun-boot-core/src/main/java/com/mugsun/boot/auth/ForgetPasswordService.java",
		"mugsun-boot-core/src/main/java/com/mugsun/boot/auth/TwoFactorService.java",
		"mugsun-boot-core/src/main/java/com/mugsun/boot/system/service/SocialService.java",
		"mugsun-boot-ai/src/main/java/com/mugsun/boot/ai/service/AiSecretBizService.java",
		"mugsun-boot-ai/src/main/java/com/mugsun/boot/ai/service/AiAppBizService.java"})
	void credentialGeneratorsUseSecureRandom(String file) throws IOException {
		Path source = Path.of("..").toAbsolutePath().normalize().resolve(file);
		assertThat(source).as("类改名或挪位置时同步这里的清单").exists();
		Matcher m = WEAK.matcher(Files.readString(source, StandardCharsets.UTF_8));
		String weak = m.find() ? m.group() : null;
		assertThat(weak).as(file + " 用了非安全随机数").isNull();
	}
}
