package com.mugsun.boot.ai.support;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 知识库上传相关配置。
 * {@code mugsun.ai.kb.max-upload-bytes}：单文件上限，默认 20MB；直接贴正文时正文的字节数也按这个上限。
 * {@code mugsun.ai.kb.max-segments-per-asset}：一份资料最多切成多少段，默认 5000。
 */
@Component
@ConfigurationProperties(prefix = "mugsun.ai.kb")
public class AiKbProperties {

	private static final long DEFAULT_MAX_UPLOAD_BYTES = 20L * 1024 * 1024;
	private static final int DEFAULT_MAX_SEGMENTS_PER_ASSET = 5000;

	/** 单文件默认 20MB，与通用附件上限对齐；可配置下调或略上调 */
	private long maxUploadBytes = DEFAULT_MAX_UPLOAD_BYTES;

	/**
	 * 分段是在上传请求里逐段入库、同步向量化的，段数太多会一直占着请求线程和向量模型额度。
	 * 5000 段按默认分段长度 500 算大约是 200 多万字的正文。
	 */
	private int maxSegmentsPerAsset = DEFAULT_MAX_SEGMENTS_PER_ASSET;

	public long getMaxUploadBytes() {
		return maxUploadBytes;
	}

	public void setMaxUploadBytes(long maxUploadBytes) {
		this.maxUploadBytes = maxUploadBytes <= 0 ? DEFAULT_MAX_UPLOAD_BYTES : maxUploadBytes;
	}

	public int getMaxSegmentsPerAsset() {
		return maxSegmentsPerAsset;
	}

	public void setMaxSegmentsPerAsset(int maxSegmentsPerAsset) {
		this.maxSegmentsPerAsset = maxSegmentsPerAsset <= 0 ? DEFAULT_MAX_SEGMENTS_PER_ASSET : maxSegmentsPerAsset;
	}
}
