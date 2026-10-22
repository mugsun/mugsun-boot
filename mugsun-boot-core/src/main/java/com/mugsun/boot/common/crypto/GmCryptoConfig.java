package com.mugsun.boot.common.crypto;

import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 国密总配置：国密开关 + SM2 传输密钥注入。
 * <p>密钥一律**外部注入**（环境变量 / 配置中心），不在 class 内硬编码；配置缺失时启动**生成一对并告警**
 * （仅便于本地起步，生产必须注入固定密钥，否则重启后旧密文无法解密）。
 * <p>{@code mugsun.crypto.gm-enabled} 为国密总开关：一处切换传输 SM2、摘要 SM3 是否启用。
 */
@Component
public class GmCryptoConfig {

	private static final Logger log = LoggerFactory.getLogger(GmCryptoConfig.class);

	/** 国密总开关（传输 SM2 / 摘要 SM3 一处切） */
	@Value("${mugsun.crypto.gm-enabled:true}")
	private boolean gmEnabled;

	/** SM2 私钥（hex，64 位），环境变量 MUGSUN_SM2_PRIVATE_KEY 注入 */
	@Value("${mugsun.crypto.sm2-private-key:}")
	private String sm2PrivateKey;

	/** SM2 公钥（hex，04+128 位未压缩点），环境变量 MUGSUN_SM2_PUBLIC_KEY 注入 */
	@Value("${mugsun.crypto.sm2-public-key:}")
	private String sm2PublicKey;

	/** 审计签名 SM2 私钥（hex），环境变量 MUGSUN_SIGN_PRIVATE_KEY 注入——与传输密钥分离（审计签名须持久稳定，传输可轮换） */
	@Value("${mugsun.crypto.sign-private-key:}")
	private String signPrivateKey;

	/** 审计签名 SM2 公钥（hex），环境变量 MUGSUN_SIGN_PUBLIC_KEY 注入 */
	@Value("${mugsun.crypto.sign-public-key:}")
	private String signPublicKey;

	/** 本地密钥目录（mugsun.home / MUGSUN_HOME，默认 ~/.mugsun）；审计签名密钥没配时自动生成到这里 */
	@Value("${mugsun.home:}")
	private String home;

	/** 自动生成的审计签名密钥文件名；与接口传输密钥（api-transport.key）分两个文件存，任何接口都不下发 */
	public static final String SIGN_KEY_FILE = "audit-sign-sm2.key";

	private static final java.util.regex.Pattern SIGN_FILE_FORMAT =
		java.util.regex.Pattern.compile("(?s)^[0-9a-fA-F]{1,64}\\s+04[0-9a-fA-F]{128}$");

	/** 审计签名密钥文件（自动生成时）；配置注入时为 null */
	private java.nio.file.Path signKeyFile;

	/** 生产严格模式：开启后 SM2 传输密钥缺失即拒绝启动；审计签名密钥缺失改为自动生成并持久化，不再拒绝 */
	@Value("${mugsun.crypto.strict-keys:false}")
	private boolean strictKeys;

	@PostConstruct
	public void init() {
		GmDigestUtil.setGmEnabled(gmEnabled);
		if (sm2PrivateKey != null && !sm2PrivateKey.isBlank()
			&& sm2PublicKey != null && !sm2PublicKey.isBlank()) {
			Sm2Util.init(sm2PrivateKey.trim(), sm2PublicKey.trim());
			log.info("SM2 传输密钥已从外部配置注入");
		} else if (strictKeys) {
			throw new IllegalStateException("strict-keys 模式：未注入 SM2 传输密钥（MUGSUN_SM2_PRIVATE_KEY/MUGSUN_SM2_PUBLIC_KEY），拒绝启动");
		} else {
			String[] pair = Sm2Util.generateKeyPair();
			Sm2Util.init(pair[0], pair[1]);
			log.warn("未注入 SM2 传输密钥，已临时生成一对（生产请配置 MUGSUN_SM2_PRIVATE_KEY/MUGSUN_SM2_PUBLIC_KEY，"
				+ "否则重启后历史密文不可解）。当前公钥={}", pair[1]);
		}
		// 审计签名密钥：配置注入优先；没配就自动生成并持久化到 <mugsun.home>/audit-sign-sm2.key，重启复用（不再拒绝启动）
		if (signPrivateKey != null && !signPrivateKey.isBlank()
			&& signPublicKey != null && !signPublicKey.isBlank()) {
			Sm2Util.initSign(signPrivateKey.trim(), signPublicKey.trim());
			log.info("审计签名密钥已从外部配置注入（独立于传输密钥）");
		} else {
			initSignFromLocalFile();
		}
	}

	private void initSignFromLocalFile() {
		java.nio.file.Path file = com.mugsun.core.web.crypto.LocalKeyFiles.resolveHome(home).resolve(SIGN_KEY_FILE);
		var loaded = com.mugsun.core.web.crypto.LocalKeyFiles.loadOrCreate(file, () -> {
			String[] pair = Sm2Util.generateKeyPair();
			return pair[0] + "\n" + pair[1];
		}, v -> SIGN_FILE_FORMAT.matcher(v).matches(), "审计签名密钥");
		String[] parts = loaded.content().split("\\s+");
		Sm2Util.initSign(parts[0], parts[1]);
		signKeyFile = loaded.file();
		if (loaded.created()) {
			log.info("未配置 MUGSUN_SIGN_PRIVATE_KEY/MUGSUN_SIGN_PUBLIC_KEY，已自动生成审计签名密钥并保存到 {}", loaded.file());
		}
		log.warn("审计签名当前使用自动生成的密钥（文件 {}）。Docker 不给该目录挂卷时每次重启都会换新密钥、历史签名验不过；"
			+ "多实例各自生成会互相验不过：这两种部署请配置 MUGSUN_SIGN_PRIVATE_KEY/MUGSUN_SIGN_PUBLIC_KEY 或挂共享卷", loaded.file());
		if (com.mugsun.core.web.crypto.LocalKeyFiles.inContainer()) {
			log.warn("检测到容器环境且没配审计签名密钥：请配置 MUGSUN_SIGN_PRIVATE_KEY/MUGSUN_SIGN_PUBLIC_KEY，或给 {} 挂持久卷",
				loaded.file().getParent());
		}
	}

	/** 自动生成时的审计签名密钥文件；配置注入时为 null */
	public java.nio.file.Path signKeyFile() {
		return signKeyFile;
	}

	public boolean isGmEnabled() {
		return gmEnabled;
	}
}
