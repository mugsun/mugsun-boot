package com.mugsun.boot.system.entity;

import com.mugsun.boot.common.crypto.Sm4TypeHandler;
import com.mugsun.core.mybatis.base.BaseEntity;
import com.mybatisflex.annotation.Column;
import com.mybatisflex.annotation.Table;

/**
 * 对象存储配置：category 固定键（local/minio/aliyun）映射 Java 配置类，禁类名入库；
 * AK/SK 凭据 SM4 密文落库、查询自动解密。
 */
@Table("sys_oss")
public class SysOss extends BaseEntity {

	private String name;
	private String ossCode;
	private String category;
	private String endpoint;
	/** 访问密钥：SM4 密文落库、查询自动解密 */
	@Column(typeHandler = Sm4TypeHandler.class)
	private String accessKey;
	/** 密钥：SM4 密文落库、查询自动解密；管理端 page/detail 不回传 */
	@Column(typeHandler = Sm4TypeHandler.class)
	private String secretKey;
	private String bucketName;
	private String domain;
	private String storagePath;
	private Integer status;
	private String remark;

	public String getName() {
		return name;
	}

	public void setName(String name) {
		this.name = name;
	}

	public String getOssCode() {
		return ossCode;
	}

	public void setOssCode(String ossCode) {
		this.ossCode = ossCode;
	}

	public String getCategory() {
		return category;
	}

	public void setCategory(String category) {
		this.category = category;
	}

	public String getEndpoint() {
		return endpoint;
	}

	public void setEndpoint(String endpoint) {
		this.endpoint = endpoint;
	}

	public String getAccessKey() {
		return accessKey;
	}

	public void setAccessKey(String accessKey) {
		this.accessKey = accessKey;
	}

	public String getSecretKey() {
		return secretKey;
	}

	public void setSecretKey(String secretKey) {
		this.secretKey = secretKey;
	}

	public String getBucketName() {
		return bucketName;
	}

	public void setBucketName(String bucketName) {
		this.bucketName = bucketName;
	}

	public String getDomain() {
		return domain;
	}

	public void setDomain(String domain) {
		this.domain = domain;
	}

	public String getStoragePath() {
		return storagePath;
	}

	public void setStoragePath(String storagePath) {
		this.storagePath = storagePath;
	}

	public Integer getStatus() {
		return status;
	}

	public void setStatus(Integer status) {
		this.status = status;
	}

	public String getRemark() {
		return remark;
	}

	public void setRemark(String remark) {
		this.remark = remark;
	}
}
