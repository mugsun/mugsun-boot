package com.mugsun.boot.system.file;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Clock;
import java.util.Base64;

/**
 * 本地存储直出链接的短时签名：s = base64url(HMAC-SHA256(secret, relPath + "\n" + e))。
 * relPath 为 urlPrefix 之后的对象相对路径（含 base-path），e 为过期 epoch 秒。
 */
@Component
public class LocalFileUrlSigner {

	private static final Logger log = LoggerFactory.getLogger(LocalFileUrlSigner.class);
	static final String PARAM_EXPIRE = "e";
	static final String PARAM_SIGN = "s";
	private static final int MIN_SECRET_LEN = 32;

	private final LocalFileAccessProperties properties;
	private final byte[] key;
	private Clock clock = Clock.systemUTC();

	public LocalFileUrlSigner(LocalFileAccessProperties properties) {
		this.properties = properties;
		String secret = properties.getSignSecret();
		if (secret == null || secret.isBlank()) {
			byte[] random = new byte[32];
			new SecureRandom().nextBytes(random);
			this.key = random;
			log.warn("未配置 mugsun.file.local-access.sign-secret，已随机生成本地文件签名密钥：仅适合单实例，重启后旧链接失效");
		} else {
			if (secret.length() < MIN_SECRET_LEN) {
				throw new IllegalStateException("mugsun.file.local-access.sign-secret 至少需要 " + MIN_SECRET_LEN + " 个字符");
			}
			this.key = secret.getBytes(StandardCharsets.UTF_8);
		}
	}

	void setClock(Clock clock) {
		this.clock = clock;
	}

	/** 给对象相对路径签发查询串（不含 ?） */
	public String signQuery(String relPath) {
		long expire = clock.instant().getEpochSecond() + Math.max(1, properties.getExpireSeconds());
		return PARAM_EXPIRE + "=" + expire + "&" + PARAM_SIGN + "=" + hmac(relPath, expire);
	}

	/** 在登记 url 后追加签名；url 必须以 urlPrefix 后的 relPath 结尾 */
	public String signUrl(String url, String relPath) {
		return url + (url.contains("?") ? "&" : "?") + signQuery(relPath);
	}

	/** 校验签名与过期；任何解析失败一律 false */
	public boolean verify(String relPath, String expireRaw, String sign) {
		if (relPath == null || expireRaw == null || sign == null) {
			return false;
		}
		long expire;
		try {
			expire = Long.parseLong(expireRaw);
		} catch (NumberFormatException e) {
			return false;
		}
		if (expire < clock.instant().getEpochSecond()) {
			return false;
		}
		byte[] expected = hmac(relPath, expire).getBytes(StandardCharsets.US_ASCII);
		return MessageDigest.isEqual(expected, sign.getBytes(StandardCharsets.US_ASCII));
	}

	private String hmac(String relPath, long expire) {
		try {
			Mac mac = Mac.getInstance("HmacSHA256");
			mac.init(new SecretKeySpec(key, "HmacSHA256"));
			byte[] out = mac.doFinal((relPath + "\n" + expire).getBytes(StandardCharsets.UTF_8));
			return Base64.getUrlEncoder().withoutPadding().encodeToString(out);
		} catch (GeneralSecurityException e) {
			throw new IllegalStateException("HmacSHA256 不可用", e);
		}
	}
}
