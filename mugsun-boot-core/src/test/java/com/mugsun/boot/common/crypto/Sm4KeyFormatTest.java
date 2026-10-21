package com.mugsun.boot.common.crypto;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.charset.StandardCharsets;
import java.util.HexFormat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** MUGSUN_SM4_KEY 只认三种写法，其他一律拒绝启动（不补零、不截断） */
class Sm4KeyFormatTest {

	@Test
	@DisplayName("32 位 hex（大小写）、规范 base64、16 字符旧写法都能启动")
	void threeValidFormats() {
		assertThat(HexFormat.of().formatHex(Sm4Util.parseKey("000102030405060708090A0B0C0D0E0F")))
			.isEqualTo("000102030405060708090a0b0c0d0e0f");
		assertThat(HexFormat.of().formatHex(Sm4Util.parseKey("  AAcOFRwjKjE4P0ZNVFtiaQ==\n")))
			.isEqualTo("00070e151c232a31383f464d545b6269");
		assertThat(Sm4Util.parseKey("abcdefghijklmnop")).isEqualTo("abcdefghijklmnop".getBytes(StandardCharsets.US_ASCII));
		Sm4Util.init("000102030405060708090a0b0c0d0e0f");
		assertThat(Sm4Util.decrypt(Sm4Util.encrypt("身份证"))).isEqualTo("身份证");
	}

	@ParameterizedTest
	@ValueSource(strings = {"a", "short-key", "abcdefghijklmno", "abcdefghijklmnopq",
		"0000000000000000000000000000000", "000000000000000000000000000000000",
		"AAcOFRwjKjE4P0ZNVFtiaQ", "AAcOFRwjKjE4P0ZNVFtiaR==", "eHh4eHh4eHh4eHh4eHh4eHh4eHh4eHh4",
		"0001020304050607 08090a0b0c0d0e0f", "this-is-a-much-longer-key-than-sixteen"})
	@DisplayName("短密钥、长密钥、不规范 base64、中间带空白：拒绝启动")
	void rejected(String key) {
		assertThatThrownBy(() -> Sm4Util.init(key)).isInstanceOf(IllegalStateException.class)
			.hasMessageContaining("MUGSUN_SM4_KEY");
	}

	@Test
	@DisplayName("缺失或只有空白：拒绝启动")
	void missing() {
		assertThatThrownBy(() -> Sm4Util.parseKey("   ")).isInstanceOf(IllegalStateException.class);
		assertThatThrownBy(() -> Sm4Util.parseKey(null)).isInstanceOf(IllegalStateException.class);
	}

	@Test
	@DisplayName("旧版补零派生只给迁移工具用：短密钥补零到 16 字节")
	void legacyPadded() {
		assertThat(Sm4Util.legacyPaddedKey("ab")).hasSize(16).startsWith((byte) 'a', (byte) 'b', (byte) 0);
	}
}
