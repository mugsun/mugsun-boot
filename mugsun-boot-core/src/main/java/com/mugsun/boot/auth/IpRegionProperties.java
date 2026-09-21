package com.mugsun.boot.auth;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 离线 IP 归属地（ip2region）配置。
 * xdb 数据文件（约 11MB）不入库：官方仅经 GitHub 仓库 data/ 分发且不定期更新，
 * 由部署方下载 ip2region_v4.xdb 至服务器本地路径后经 xdb-path 指向（开关缺省关闭）。
 */
@Component
@ConfigurationProperties(prefix = "mugsun.ip2region")
public class IpRegionProperties {

	/** 归属地解析总开关（缺省关闭；开启需 xdb-path 指向真实 ip2region_v4.xdb） */
	private boolean enabled = false;

	/** ip2region_v4.xdb 文件绝对路径（官方 GitHub lionsoul2014/ip2region 仓库 data/ 目录下载） */
	private String xdbPath;

	public boolean isEnabled() {
		return enabled;
	}

	public void setEnabled(boolean enabled) {
		this.enabled = enabled;
	}

	public String getXdbPath() {
		return xdbPath;
	}

	public void setXdbPath(String xdbPath) {
		this.xdbPath = xdbPath;
	}
}
