package com.mugsun.boot.ai.support;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 知识库上传相关配置。
 * {@code mugsun.ai.kb.max-upload-bytes}：单文件上限，默认 20MB。
 */
@Component
@ConfigurationProperties(prefix = "mugsun.ai.kb")
public class AiKbProperties {

	/** 单文件默认 20MB，与通用附件上限对齐；可配置下调或略上调 */
	private long maxUploadBytes = 20L * 1024 * 1024;

	public long getMaxUploadBytes() {
		return maxUploadBytes;
	}

	public void setMaxUploadBytes(long maxUploadBytes) {
		this.maxUploadBytes = maxUploadBytes <= 0 ? 20L * 1024 * 1024 : maxUploadBytes;
	}
}
