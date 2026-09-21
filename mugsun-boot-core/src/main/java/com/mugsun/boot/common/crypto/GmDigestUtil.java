package com.mugsun.boot.common.crypto;

import cn.hutool.crypto.SmUtil;
import cn.hutool.crypto.digest.DigestUtil;

/**
 * 摘要门面：国密开关开启用 <b>SM3</b>，关闭回退 <b>SHA-256</b>。开关随 {@link GmCryptoConfig} 一处切换。
 */
public final class GmDigestUtil {

	private static volatile boolean gm = true;

	private GmDigestUtil() {
	}

	static void setGmEnabled(boolean enabled) {
		gm = enabled;
	}

	/** 摘要（hex）：SM3 或 SHA-256（按国密开关） */
	public static String digest(String text) {
		if (text == null) {
			return null;
		}
		return gm ? SmUtil.sm3(text) : DigestUtil.sha256Hex(text);
	}

	/** 当前生效的摘要算法名 */
	public static String algorithm() {
		return gm ? "SM3" : "SHA-256";
	}
}
