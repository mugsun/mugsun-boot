package com.mugsun.boot.auth;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.lionsoul.ip2region.service.Config;
import org.lionsoul.ip2region.service.Ip2Region;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * 离线 IP 归属地解析（ip2region xdb，仅 IPv4 库）。
 * 启动时按配置一次性加载并发安全的 Ip2Region 查询服务（VectorIndex 缓存，512KiB）；
 * 开关关闭 / xdb 缺失 / 解析失败一律返回 null——归属地是展示增强，绝不阻断登录链路。
 */
@Service
public class IpRegionService {

	private static final Logger log = LoggerFactory.getLogger(IpRegionService.class);

	private final IpRegionProperties properties;
	private Ip2Region ip2Region;

	public IpRegionService(IpRegionProperties properties) {
		this.properties = properties;
	}

	@PostConstruct
	void init() {
		if (!properties.isEnabled()) {
			return;
		}
		String xdbPath = properties.getXdbPath();
		if (xdbPath == null || xdbPath.isBlank()) {
			log.error("mugsun.ip2region.enabled=true 但未配置 xdb-path，IP 归属地解析停用");
			return;
		}
		try {
			Config v4Config = Config.custom()
				.setCachePolicy(Config.VIndexCache)
				.setXdbPath(xdbPath)
				.asV4();
			ip2Region = Ip2Region.create(v4Config, null);
			log.info("ip2region 离线库已加载：{}", xdbPath);
		} catch (Exception e) {
			log.error("ip2region xdb 加载失败（{}）：{}，IP 归属地解析停用", xdbPath, e.getMessage());
		}
	}

	/** 解析归属地展示串（国家 省份 城市，跳过 0 占位段）；不可解析返回 null */
	public String resolve(String ip) {
		if (ip2Region == null || ip == null || ip.isBlank()) {
			return null;
		}
		try {
			return format(ip2Region.search(ip));
		} catch (InterruptedException e) {
			// 查询等待 searcher 被中断：恢复中断标记，按不可解析处理
			Thread.currentThread().interrupt();
			return null;
		} catch (Exception e) {
			log.warn("IP 归属地解析失败 ip={}：{}", ip, e.getMessage());
			return null;
		}
	}

	/** xdb 区域串 国家|省份|城市|ISP|iso码 → 展示串（0 占位段剔除，全空/未命中归 null） */
	private String format(String region) {
		if (region == null || region.isBlank()) {
			return null;
		}
		String[] segments = region.split("\\|");
		StringBuilder sb = new StringBuilder();
		// 仅取 国家/省份/城市 三段展示（ISP/iso 码不上屏）
		for (int i = 0; i < Math.min(segments.length, 3); i++) {
			String segment = segments[i];
			if (segment != null && !segment.isBlank() && !"0".equals(segment)) {
				if (sb.length() > 0) {
					sb.append(' ');
				}
				sb.append(segment.trim());
			}
		}
		return sb.length() == 0 ? null : sb.toString();
	}

	@PreDestroy
	void destroy() {
		if (ip2Region != null) {
			try {
				ip2Region.close();
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
			} catch (Exception e) {
				log.warn("ip2region 关闭异常：{}", e.getMessage());
			}
		}
	}
}
