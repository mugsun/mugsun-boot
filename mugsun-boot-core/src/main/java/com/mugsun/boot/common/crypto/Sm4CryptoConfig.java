package com.mugsun.boot.common.crypto;

import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 启动时以配置密钥初始化 {@link Sm4Util}，供 {@link Sm4TypeHandler} 字段加解密使用。
 * 密钥必须环境变量注入；缺省回落内置开发默认值并 ERROR 告警（strict-keys 模式直接拒绝启动）。
 */
@Component
public class Sm4CryptoConfig {

	private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(Sm4CryptoConfig.class);

	/** 内置开发默认值（公开串，仅本地起步；生产使用即 PII 裸奔） */
	private static final String DEV_FALLBACK = "mugsun-sm4-key16";

	/** SM4 字段加密密钥（16 字节）；环境变量 MUGSUN_SM4_KEY 注入，勿硬编码生产密钥 */
	@Value("${mugsun.crypto.sm4-key:}")
	private String sm4Key;

	/** 生产严格模式：密钥缺失即拒绝启动 */
	@Value("${mugsun.crypto.strict-keys:false}")
	private boolean strictKeys;

	@PostConstruct
	public void init() {
		if (sm4Key == null || sm4Key.isBlank()) {
			if (strictKeys) {
				throw new IllegalStateException("strict-keys 模式：未注入 SM4 字段加密密钥（MUGSUN_SM4_KEY），拒绝启动");
			}
			log.error("未注入 SM4 字段加密密钥，回落内置开发默认值（仅本地可用；生产必须配置 MUGSUN_SM4_KEY，"
				+ "否则库内 PII 以公开密钥加密）");
			Sm4Util.init(DEV_FALLBACK);
			return;
		}
		Sm4Util.init(sm4Key.trim());
	}
}
