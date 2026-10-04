package com.mugsun.boot.common.crypto;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * sm-crypto 的 C1 不带 04。X 坐标有 1/256 的概率以 04 开头，解密不能按第一个字节判断前缀。
 */
class Sm2UtilDecryptTest {

	private static final String PLAIN = "123456";

	@BeforeAll
	static void keys() {
		String[] pair = Sm2Util.generateKeyPair();
		Sm2Util.init(pair[0], pair[1]);
	}

	@Test
	void decryptsWhenXCoordinateStartsWith04() {
		int leading = 0;
		for (int i = 0; i < 2048; i++) {
			String cipher = Sm2Util.encrypt(PLAIN);
			if (cipher.startsWith("04")) {
				leading++;
			}
			assertThat(Sm2Util.decrypt(cipher)).isEqualTo(PLAIN);
			assertThat(Sm2Util.decrypt("04" + cipher)).isEqualTo(PLAIN);
		}
		assertThat(leading).as("这一轮必须打到 X 坐标以 04 开头的密文").isGreaterThan(0);
	}
}
