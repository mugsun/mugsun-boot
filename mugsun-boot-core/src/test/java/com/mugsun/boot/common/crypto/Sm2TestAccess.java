package com.mugsun.boot.common.crypto;

import org.springframework.test.util.ReflectionTestUtils;

/** 测试专用：读出当前审计签名密钥，用来断言它们没出现在任何接口响应里 */
public final class Sm2TestAccess {

	private Sm2TestAccess() {
	}

	public static String[] signKeys() {
		return new String[]{
			(String) ReflectionTestUtils.getField(Sm2Util.class, "signPrivateKeyHex"),
			(String) ReflectionTestUtils.getField(Sm2Util.class, "signPublicKeyHex")};
	}
}
