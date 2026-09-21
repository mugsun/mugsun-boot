package com.mugsun.boot.common.crypto;

import cn.hutool.core.util.HexUtil;
import cn.hutool.crypto.SmUtil;
import cn.hutool.crypto.asymmetric.KeyType;
import cn.hutool.crypto.asymmetric.SM2;
import org.bouncycastle.crypto.engines.SM2Engine;

/**
 * SM2 国密非对称传输工具（登录/改密/注册密码前端公钥加密、后端私钥解密）。
 * <p>与前端 sm-crypto 跨库互通：sm-crypto {@code doEncrypt(pwd, pub, 1)} 默认 C1C3C2 且密文不含 {@code 04} 前缀；
 * Hutool(底层 BC SM2Engine) 解密要求密文带 {@code 04} 前缀，故解密前补齐；mode 显式对齐 {@link SM2Engine.Mode#C1C3C2}
 * 防依赖升降级。为兼容历史/多前端来源，解密走「补/不补 04 × C1C3C2/C1C2C3」四组合兜底（C3 校验错组合必抛异常，绝不误判）。
 * <p>密钥由 {@link Sm2CryptoConfig} 启动时以配置注入；配置缺失时启动生成一对并告警（生产须外部注入固定密钥）。
 */
public final class Sm2Util {

	private static volatile String privateKeyHex;
	private static volatile String publicKeyHex;
	/** 审计签名密钥（与传输密钥分离；未独立注入时回退传输密钥） */
	private static volatile String signPrivateKeyHex;
	private static volatile String signPublicKeyHex;

	private Sm2Util() {
	}

	/** 由 Spring 启动时以配置密钥（hex）初始化 */
	static void init(String privHex, String pubHex) {
		privateKeyHex = privHex;
		publicKeyHex = pubHex;
		// 缺省回退：签名密钥跟随传输密钥
		signPrivateKeyHex = privHex;
		signPublicKeyHex = pubHex;
	}

	/** 审计签名密钥独立注入（与传输密钥生命周期解耦） */
	static void initSign(String privHex, String pubHex) {
		signPrivateKeyHex = privHex;
		signPublicKeyHex = pubHex;
	}

	/** 生成一对 SM2 密钥（hex），[0]=私钥 d(64hex)、[1]=公钥 04+128hex 未压缩点 */
	static String[] generateKeyPair() {
		SM2 sm2 = SmUtil.sm2();
		return new String[] {sm2.getDHex(), HexUtil.encodeHexStr(sm2.getQ(false))};
	}

	/** 前端加密用公钥（04 开头 130 hex 未压缩点） */
	public static String publicKey() {
		return publicKeyHex;
	}

	/** 用公钥加密（主要供联调/自测；生产加密在前端 sm-crypto 完成） */
	public static String encrypt(String plain) {
		if (plain == null) {
			return null;
		}
		SM2 sm2 = SmUtil.sm2(null, publicKeyHex);
		sm2.setMode(SM2Engine.Mode.C1C3C2);
		// Hutool 加密输出带 04 前缀；剥掉以对齐前端 sm-crypto 不带 04 的约定
		String hex = sm2.encryptHex(plain, KeyType.PublicKey);
		return hex.startsWith("04") ? hex.substring(2) : hex;
	}

	/**
	 * 私钥解密前端 SM2 密文（hex，可带或不带 04 前缀）。健壮兜底四组合，任一成功即返回。
	 */
	public static String decrypt(String cipherHex) {
		if (cipherHex == null || cipherHex.isEmpty()) {
			return cipherHex;
		}
		String bare = cipherHex.startsWith("04") ? cipherHex.substring(2) : cipherHex;
		String withPrefix = "04" + bare;
		SM2Engine.Mode[] modes = {SM2Engine.Mode.C1C3C2, SM2Engine.Mode.C1C2C3};
		String[] candidates = {withPrefix, cipherHex};
		for (SM2Engine.Mode mode : modes) {
			for (String c : candidates) {
				String hex = c.startsWith("04") ? c : "04" + c;
				try {
					SM2 sm2 = SmUtil.sm2(privateKeyHex, publicKeyHex);
					sm2.setMode(mode);
					return sm2.decryptStr(hex, KeyType.PrivateKey);
				} catch (Exception ignore) {
					// 换下一组合；不外泄内部异常细节
				}
			}
		}
		// 不回显内部实现细节，仅给中性提示（防密文格式探测）
		throw new com.mugsun.core.tool.exception.ServiceException("SM2 密文无效");
	}

	/** SM2 私钥签名（对 hex 数据）→ 签名 hex，供审计记录防伪造（用审计签名密钥，与传输解耦） */
	public static String signHex(String dataHex) {
		SM2 sm2 = SmUtil.sm2(signPrivateKeyHex, signPublicKeyHex);
		return sm2.signHex(dataHex);
	}

	/** SM2 公钥验签（hex 数据 + hex 签名）→ 是否有效（用审计签名密钥，与传输解耦） */
	public static boolean verifyHex(String dataHex, String signHex) {
		if (signHex == null || signHex.isBlank()) {
			return false;
		}
		try {
			SM2 sm2 = SmUtil.sm2(signPrivateKeyHex, signPublicKeyHex);
			return sm2.verifyHex(dataHex, signHex);
		} catch (Exception e) {
			return false;
		}
	}
}
