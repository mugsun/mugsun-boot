package com.mugsun.boot.common.crypto;

import cn.hutool.core.util.HexUtil;
import cn.hutool.crypto.SmUtil;
import cn.hutool.crypto.asymmetric.KeyType;
import cn.hutool.crypto.asymmetric.SM2;
import org.bouncycastle.asn1.gm.GMNamedCurves;
import org.bouncycastle.crypto.engines.SM2Engine;
import org.bouncycastle.math.ec.ECCurve;

/**
 * SM2 国密非对称传输工具（登录/改密/注册密码前端公钥加密、后端私钥解密）。
 * <p>线上只有一种密文：sm-crypto {@code doEncrypt(pwd, pub, 1)}，C1C3C2，C1 为 128 hex、不含 {@code 04}。
 * BouncyCastle 要求未压缩点带 {@code 04}。是否已带前缀看前 65 字节能不能解成曲线上的点，不能看第一个字节：
 * X 坐标本身有 1/256 的概率以 {@code 04} 开头，按开头判断会把这一字节吃掉。
 * <p>密钥由 {@link GmCryptoConfig} 启动时以配置注入；配置缺失时启动生成一对并告警（生产须外部注入固定密钥）。
 */
public final class Sm2Util {

	/** 未压缩点：1 字节标记 + 32 字节 X + 32 字节 Y */
	private static final int UNCOMPRESSED_POINT_HEX = 130;
	private static final ECCurve SM2_CURVE = GMNamedCurves.getByName("sm2p256v1").getCurve();

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
	 * 私钥解密。sm-crypto 的密文无条件补 {@code 04}；已经是未压缩点的密文保持原样。模式只认 C1C3C2。
	 */
	public static String decrypt(String cipherHex) {
		if (cipherHex == null || cipherHex.isEmpty()) {
			return cipherHex;
		}
		String hex = cipherHex.trim();
		String normalized = hasUncompressedPoint(hex) ? hex : "04" + hex;
		try {
			SM2 sm2 = SmUtil.sm2(privateKeyHex, publicKeyHex);
			sm2.setMode(SM2Engine.Mode.C1C3C2);
			return sm2.decryptStr(normalized, KeyType.PrivateKey);
		} catch (Exception e) {
			throw new com.mugsun.core.tool.exception.ServiceException("SM2 密文无效");
		}
	}

	/** 前 65 字节能解成 SM2 曲线上的点，才说明 {@code 04} 是点标记而不是 X 坐标的第一个字节。 */
	private static boolean hasUncompressedPoint(String hex) {
		if (hex.length() < UNCOMPRESSED_POINT_HEX || (hex.length() & 1) == 1) {
			return false;
		}
		try {
			SM2_CURVE.decodePoint(org.bouncycastle.util.encoders.Hex.decode(hex.substring(0, UNCOMPRESSED_POINT_HEX)));
			return true;
		} catch (Exception e) {
			return false;
		}
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
