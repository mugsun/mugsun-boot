package com.mugsun.boot.common.crypto;

import cn.hutool.crypto.SmUtil;
import cn.hutool.crypto.symmetric.SM4;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Base64;
import java.util.HexFormat;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Pattern;

/**
 * SM4 国密对称加解密工具（字段加密用，ECB 确定性密文以支持等值查询、兼容历史数据）。
 * ECB 只作为例外保留给 sys_user.id_card，其余字段下一轮迁到 SM4-GCM，见安全文档。
 * 密钥由 {@link Sm4CryptoConfig} 启动时注入。接口/传输加密（SM4-GCM）见 mugsun-core 的 ApiCryptoService。
 */
public final class Sm4Util {

	private static final Logger log = LoggerFactory.getLogger(Sm4Util.class);

	private static volatile SM4 sm4;

	private static final Pattern HEX32 = Pattern.compile("[0-9a-fA-F]{32}");
	private static final Pattern BASE64_16 = Pattern.compile("[A-Za-z0-9+/]{22}==");

	/** 启动以来解密失败（密钥失配或密文损坏）次数；迁移后核对应为 0 */
	private static final AtomicLong DECRYPT_FAILURES = new AtomicLong();

	private Sm4Util() {
	}

	/** 由 Spring 启动时以配置密钥初始化；格式见 {@link #parseKey(String)}，不合法直接拒绝启动 */
	static void init(String key) {
		sm4 = SmUtil.sm4(parseKey(key));
	}

	/**
	 * 解析 MUGSUN_SM4_KEY，只接受三种写法（先去掉前后空白）：
	 * <ol>
	 *   <li>32 位 hex（大小写均可）；</li>
	 *   <li>带填充的标准 base64，解码后正好 16 字节，且重新编码后与输入一致；</li>
	 *   <li>过渡期兼容：UTF-8 编码后正好 16 字节的旧写法（派生方式不变），用到时打 WARN，下一版移除。</li>
	 * </ol>
	 * 其他一律拒绝：不补零、不截断。旧版补零 / 截断过的短密钥数据用 {@link Sm4KeyMigrationTool} 迁移。
	 */
	public static byte[] parseKey(String key) {
		if (key == null || key.strip().isEmpty()) {
			throw new IllegalStateException("未配置 SM4 字段加密密钥 MUGSUN_SM4_KEY，拒绝启动");
		}
		String k = key.strip();
		if (HEX32.matcher(k).matches()) {
			return HexFormat.of().parseHex(k);
		}
		if (BASE64_16.matcher(k).matches()) {
			byte[] raw = Base64.getDecoder().decode(k);
			if (raw.length == 16 && Base64.getEncoder().encodeToString(raw).equals(k)) {
				return raw;
			}
		}
		byte[] utf8 = k.getBytes(StandardCharsets.UTF_8);
		if (utf8.length == 16) {
			log.warn("MUGSUN_SM4_KEY 使用旧的 16 字符写法，本版仍兼容、下一版只接受 32 位 hex；"
				+ "请按文档「不兼容变更」用迁移工具换成 openssl rand -hex 16 生成的新密钥");
			return utf8;
		}
		throw new IllegalStateException("MUGSUN_SM4_KEY 格式不对：须为 32 位 hex、解码后 16 字节的标准 base64，"
			+ "或过渡期的 16 字符旧写法；不补零不截断，拒绝启动");
	}

	/** 旧版（本版之前）的派生方式：UTF-8 字节不足补零、超出截断。只给离线迁移工具还原旧数据用 */
	static byte[] legacyPaddedKey(String key) {
		return Arrays.copyOf(key.getBytes(StandardCharsets.UTF_8), 16);
	}

	/** 启动以来解密失败次数（迁移核对用） */
	public static long decryptFailures() {
		return DECRYPT_FAILURES.get();
	}

	/** 以 16 字节原始密钥初始化（本地开发密钥文件用） */
	static void initRaw(byte[] key) {
		if (key == null || key.length != 16) {
			throw new IllegalStateException("SM4 密钥须正好 16 字节");
		}
		sm4 = SmUtil.sm4(key.clone());
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
				DECRYPT_FAILURES.incrementAndGet();
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
