package com.mugsun.boot.common.crypto;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 字段加密密钥：没有内置回落；dev 用持久化的本地密钥文件，重启后能解开旧数据。 */
class Sm4CryptoConfigTest {

	private static Sm4CryptoConfig config(String key, boolean strict, String file) {
		Sm4CryptoConfig c = new Sm4CryptoConfig();
		ReflectionTestUtils.setField(c, "sm4Key", key);
		ReflectionTestUtils.setField(c, "strictKeys", strict);
		ReflectionTestUtils.setField(c, "localKeyFile", file);
		return c;
	}

	@Test
	@DisplayName("缺密钥且没设本地密钥文件：拒绝启动")
	void missingKeyRejected() {
		assertThatThrownBy(config("", false, "")::init)
			.isInstanceOf(IllegalStateException.class).hasMessageContaining("MUGSUN_SM4_KEY");
		assertThatThrownBy(config(null, true, null)::init).isInstanceOf(IllegalStateException.class);
	}

	@Test
	@DisplayName("strict-keys 开启时不认本地密钥文件")
	void strictIgnoresLocalFile(@TempDir Path dir) {
		assertThatThrownBy(config("", true, dir.resolve("k").toString())::init)
			.isInstanceOf(IllegalStateException.class);
		assertThat(dir.resolve("k")).doesNotExist();
	}

	@Test
	@DisplayName("本地密钥文件：首次生成（600 权限），重启复用同一把，旧密文仍能解开")
	void localKeyFilePersistsAcrossRestarts(@TempDir Path dir) throws IOException {
		Path f = dir.resolve(".mugsun/dev-sm4.key");
		config("", false, f.toString()).init();
		assertThat(f).exists();
		assertThat(Files.readString(f).trim()).matches("[0-9a-f]{32}");
		if (Files.getFileStore(f).supportsFileAttributeView("posix")) {
			assertThat(PosixFilePermissions.toString(Files.getPosixFilePermissions(f))).isEqualTo("rw-------");
		}
		String cipher = Sm4Util.encrypt("身份证 110101");
		String before = Files.readString(f);

		config("", false, f.toString()).init();
		assertThat(Files.readString(f)).isEqualTo(before);
		assertThat(Sm4Util.decrypt(cipher)).isEqualTo("身份证 110101");
	}

	@Test
	@DisplayName("本地密钥文件内容格式不对：拒绝启动")
	void malformedLocalFileRejected(@TempDir Path dir) throws IOException {
		Path f = dir.resolve("bad.key");
		Files.writeString(f, "not-a-hex-key");
		assertThatThrownBy(config("", false, f.toString())::init).isInstanceOf(IllegalStateException.class);
	}

	@Test
	@DisplayName("源码里没有写死的 SM4 字段加密回落密钥")
	void noHardcodedFallback() throws IOException {
		String code = Files.readString(Path.of("src/main/java/com/mugsun/boot/common/crypto/Sm4CryptoConfig.java"));
		assertThat(code).doesNotContain("DEV_FALLBACK");
		assertThat(code).doesNotContainPattern("\"[!-~]{16}\"");
		assertThat(code).doesNotContainPattern("\"[0-9a-fA-F]{32}\"");
		assertThat(code).doesNotContainPattern("Sm4Util\\.init\\(\"");
	}
}
