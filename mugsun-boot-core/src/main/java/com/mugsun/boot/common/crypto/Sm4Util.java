package com.mugsun.boot.common.crypto;

import cn.hutool.crypto.SmUtil;
import cn.hutool.crypto.symmetric.SM4;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/**
 * SM4 国密对称加解密工具（字段加密用，ECB 确定性密文以支持等值查询、兼容历史数据）。
 * 密钥由 {@link Sm4CryptoConfig} 启动时注入。接口/传输加密（CBC + 随机 IV）见 mugsun-core 的 ApiCryptoService。
 */
public final class Sm4Util {

	private static final Logger log = LoggerFactory.getLogger(Sm4Util.class);

	private static volatile SM4 sm4;

	private Sm4Util() {
	}

	/** 由 Spring 启动时以配置密钥初始化（SM4 要求 16 字节密钥，不足补齐、超出截断） */
	static void init(String key) {
		byte[] raw = key.getBytes(StandardCharsets.UTF_8);
		sm4 = SmUtil.sm4(Arrays.copyOf(raw, 16));
	}

	/** 加密为 Base64 密文；null 原样返回 */
	public static String encrypt(String plain) {
		return plain == null ? null : sm4.encryptBase64(plain);
	}

	/**
	 * 解密 Base64 密文；null/空原样返回。
	 * 密钥失配/密文损坏时 fail-loud（error 日志 + 返回 null），绝不下行密文——
	 * 密文直出会把「哪些字段加密+密文形态」外发，且前端可能把密文当数据回写造成二次加密毁数据。
	 * 历史明文（加密功能上线前落库的非密文形态值）原样放行，兼容存量。
	 */
	public static String decrypt(String cipher) {
		if (cipher == null || cipher.isEmpty()) {
			return cipher;
		}
		try {
			return sm4.decryptStr(cipher, StandardCharsets.UTF_8);
		} catch (Exception e) {
			if (looksLikeCipher(cipher)) {
				log.error("SM4 解密失败（密钥失配或密文损坏），已按 fail-closed 置空：len={}", cipher.length());
				return null;
			}
			// 非密文形态（历史明文），原样返回
			return cipher;
		}
	}

	/** 密文形态判定（public：写侧防回写复用）：合法 Base64 且解码后为 SM4 分组的整数倍（16B） */
	public static boolean looksLikeCipher(String v) {
		try {
			byte[] raw = cn.hutool.core.codec.Base64.decode(v);
			return raw.length >= 16 && raw.length % 16 == 0;
		} catch (Exception e) {
			return false;
		}
	}
}
