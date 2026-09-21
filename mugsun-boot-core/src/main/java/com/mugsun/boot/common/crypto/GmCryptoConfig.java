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

	/** 生产严格模式：开启后密钥缺失即拒绝启动（防静默临时密钥致历史签名/密文作废） */
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
		// 审计签名密钥：独立注入优先；缺省回退传输密钥（同一持久密钥则签名链稳定）；
		// 二者皆无则随传输侧临时生成（重启后历史签名不可验——本地起步容忍，生产必须注入）
		if (signPrivateKey != null && !signPrivateKey.isBlank()
			&& signPublicKey != null && !signPublicKey.isBlank()) {
			Sm2Util.initSign(signPrivateKey.trim(), signPublicKey.trim());
			log.info("审计签名密钥已从外部配置注入（独立于传输密钥）");
		} else if (strictKeys) {
			throw new IllegalStateException("strict-keys 模式：未注入审计签名密钥（MUGSUN_SIGN_PRIVATE_KEY/MUGSUN_SIGN_PUBLIC_KEY），拒绝启动");
		} else {
			log.warn("未注入审计签名密钥，审计验签跟随传输密钥（生产请配置 MUGSUN_SIGN_PRIVATE_KEY/MUGSUN_SIGN_PUBLIC_KEY）");
		}
	}

	public boolean isGmEnabled() {
		return gmEnabled;
	}
}
