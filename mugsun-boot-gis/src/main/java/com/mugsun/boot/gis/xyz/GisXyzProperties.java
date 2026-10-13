package com.mugsun.boot.gis.xyz;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.ArrayList;
import java.util.List;

/**
 * 自定义 XYZ 底图的部署级配置（运维在 yml / 环境变量里定，租户管理员改不了）。
 * <p>
 * 域名白名单是防 SSRF 的第一道闸：租户只能从这里列出的域名里选瓦片地址。
 */
@ConfigurationProperties(prefix = "mugsun.gis.xyz")
public class GisXyzProperties {

	/** 允许的瓦片域名：完全相等，或是其子域（a.basemaps.cartocdn.com 命中 basemaps.cartocdn.com）。为空即禁用 xyz 底图 */
	private List<String> allowedHosts = new ArrayList<>(List.of("tile.openstreetmap.org"));
	/** 租户没保存过 xyz 配置时使用的预设（目前内置 osm）；留空表示必须先配置 */
	private String defaultPreset = GisXyzPresets.OSM;
	/** OSM 瓦片政策要求可识别的 UA；部署方应改成自己的应用名与联系方式 */
	private String userAgent = "mugsun-boot-gis/0.1 (+https://github.com/mugsun/mugsun-boot)";
	/** 单张瓦片上限（字节） */
	private int maxTileBytes = 2 * 1024 * 1024;
	/** 进程内瓦片缓存总上限（字节），0 关闭 */
	private long cacheMaxBytes = 64L * 1024 * 1024;
	/** 缓存有效期（秒） */
	private long cacheTtlSeconds = 86_400;

	public List<String> getAllowedHosts() {
		return allowedHosts;
	}

	public void setAllowedHosts(List<String> allowedHosts) {
		this.allowedHosts = allowedHosts == null ? new ArrayList<>() : allowedHosts;
	}

	public String getDefaultPreset() {
		return defaultPreset;
	}

	public void setDefaultPreset(String defaultPreset) {
		this.defaultPreset = defaultPreset;
	}

	public String getUserAgent() {
		return userAgent;
	}

	public void setUserAgent(String userAgent) {
		this.userAgent = userAgent;
	}

	public int getMaxTileBytes() {
		return maxTileBytes;
	}

	public void setMaxTileBytes(int maxTileBytes) {
		this.maxTileBytes = maxTileBytes;
	}

	public long getCacheMaxBytes() {
		return cacheMaxBytes;
	}

	public void setCacheMaxBytes(long cacheMaxBytes) {
		this.cacheMaxBytes = cacheMaxBytes;
	}

	public long getCacheTtlSeconds() {
		return cacheTtlSeconds;
	}

	public void setCacheTtlSeconds(long cacheTtlSeconds) {
		this.cacheTtlSeconds = cacheTtlSeconds;
	}
}
