package com.mugsun.boot.system.file;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Clock;
import java.util.Base64;
import java.util.HashSet;
import java.util.Set;

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
	/** 配置文件里没开直出（enable-access 不是 true）的本地存储平台名 */
	private final Set<String> closedPlatforms;
	private Clock clock = Clock.systemUTC();

	public LocalFileUrlSigner(LocalFileAccessProperties properties) {
		this(properties, null);
	}

	@org.springframework.beans.factory.annotation.Autowired
	public LocalFileUrlSigner(LocalFileAccessProperties properties, Environment env) {
		this.properties = properties;
		this.closedPlatforms = closedPlatformsOf(env);
		String secret = properties.getSignSecret() == null ? null : properties.getSignSecret().strip();
		boolean prodDirect = env != null && env.acceptsProfiles(Profiles.of("prod")) && localAccessEnabled(env);
		if (prodDirect && secret != null && !secret.isEmpty() && secret.length() < MIN_SECRET_LEN) {
			throw new IllegalStateException("prod 开启了本地存储直出，mugsun.file.local-access.sign-secret"
				+ "（MUGSUN_FILE_SIGN_SECRET）至少 " + MIN_SECRET_LEN + " 字符，当前 " + secret.length() + " 字符");
		}
		if ((secret == null || secret.isBlank()) && env != null && env.acceptsProfiles(Profiles.of("prod"))
			&& localAccessEnabled(env)) {
			throw new IllegalStateException("prod 开启了本地存储直出（x-file-storage local-plus enable-access=true），"
				+ "必须配置 mugsun.file.local-access.sign-secret（MUGSUN_FILE_SIGN_SECRET，至少 32 字符）");
		}
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

	/**
	 * 给登记 url 追加签名。签名路径直接从 url 自身的路径里取 urlPrefix 之后的部分——
	 * 与 {@link LocalFileAccessFilter} 校验时的取法同源，避免链接与签名路径不一致。
	 */
	public String signUrl(String url) {
		String rel = relPathOf(url);
		if (rel == null) {
			throw new IllegalArgumentException("文件地址不在本地直出前缀下，无法签名");
		}
		return url + (url.contains("?") ? "&" : "?") + signQuery(rel);
	}

	/** url（绝对或以 / 开头的相对地址）中 urlPrefix 之后的原始路径；不含该前缀返回 null */
	String relPathOf(String url) {
		String path;
		try {
			path = URI.create(url).getRawPath();
		} catch (IllegalArgumentException e) {
			return null;
		}
		String prefix = properties.getUrlPrefix();
		int at = path == null ? -1 : path.indexOf(prefix);
		if (at < 0) {
			return null;
		}
		return path.substring(at + prefix.length());
	}

	private static boolean localAccessEnabled(Environment env) {
		for (int i = 0; i < 16; i++) {
			if (Boolean.TRUE.equals(env.getProperty("dromara.x-file-storage.local-plus[" + i + "].enable-access",
				Boolean.class))) {
				return true;
			}
		}
		return false;
	}

	/**
	 * 这个存储平台上的文件，登记 url 能不能直接打开。配置文件里的本地存储没开直出（prod 默认）时不能：
	 * {@code /file/**} 后面没有东西输出文件，公开文件和签了名的私有地址都是 404，这时要走授权流式下载。
	 * 不在配置文件里的平台（存储配置页加的云存储、自建本地存储）地址由它自己的域名提供，一律算能打开。
	 */
	public boolean directAccessEnabled(String platform) {
		return platform == null || !closedPlatforms.contains(platform);
	}

	private static Set<String> closedPlatformsOf(Environment env) {
		Set<String> closed = new HashSet<>();
		if (env == null) {
			return closed;
		}
		for (int i = 0; i < 16; i++) {
			String prefix = "dromara.x-file-storage.local-plus[" + i + "].";
			String platform = env.getProperty(prefix + "platform");
			if (platform != null && !platform.isBlank()
				&& !Boolean.TRUE.equals(env.getProperty(prefix + "enable-access", Boolean.class))) {
				closed.add(platform.trim());
			}
		}
		return closed;
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
