package com.mugsun.boot.system.file;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 本地存储直出（x-file-storage enable-access）访问控制配置。
 * <p>公开前缀下的对象（如头像）匿名可取；其余对象必须带短时 HMAC 签名（e=过期秒级时间戳，s=签名）。
 */
@Component
@ConfigurationProperties(prefix = "mugsun.file.local-access")
public class LocalFileAccessProperties {

	/** 直出 URL 前缀，与 x-file-storage local-plus path-patterns 去掉 ** 一致 */
	private String urlPrefix = "/file/";

	/** 匿名可取的对象相对路径前缀（相对 urlPrefix，含 base-path） */
	private List<String> publicPrefixes = new ArrayList<>(List.of("mugsun/public/"));

	/**
	 * HMAC 密钥（多实例部署必须配置成同一个值，至少 32 字符）。
	 * 留空时启动随机生成，仅适合单实例开发环境，重启后旧链接失效。
	 */
	private String signSecret = "";

	/** 签名链接有效期（秒） */
	private long expireSeconds = 300;

	public String getUrlPrefix() {
		return urlPrefix;
	}

	public void setUrlPrefix(String urlPrefix) {
		this.urlPrefix = urlPrefix;
	}

	public List<String> getPublicPrefixes() {
		return publicPrefixes;
	}

	public void setPublicPrefixes(List<String> publicPrefixes) {
		this.publicPrefixes = publicPrefixes;
	}

	public String getSignSecret() {
		return signSecret;
	}

	public void setSignSecret(String signSecret) {
		this.signSecret = signSecret;
	}

	public long getExpireSeconds() {
		return expireSeconds;
	}

	public void setExpireSeconds(long expireSeconds) {
		this.expireSeconds = expireSeconds;
	}
}
