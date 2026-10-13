package com.mugsun.boot.gis.tile;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 瓦片代理通用配置（所有底图共用）。
 */
@ConfigurationProperties(prefix = "mugsun.gis.tile")
public class GisTileProperties {

	/** 每个登录用户每分钟最多取多少张瓦片；0 不限 */
	private int rateLimitPerUser = 1200;
	/** 每个客户端 IP 每分钟最多取多少张瓦片；0 不限 */
	private int rateLimitPerIp = 2400;

	public int getRateLimitPerUser() {
		return rateLimitPerUser;
	}

	public void setRateLimitPerUser(int rateLimitPerUser) {
		this.rateLimitPerUser = rateLimitPerUser;
	}

	public int getRateLimitPerIp() {
		return rateLimitPerIp;
	}

	public void setRateLimitPerIp(int rateLimitPerIp) {
		this.rateLimitPerIp = rateLimitPerIp;
	}
}
