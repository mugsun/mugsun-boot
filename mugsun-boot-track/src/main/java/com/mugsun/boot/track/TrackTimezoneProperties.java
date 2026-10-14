package com.mugsun.boot.track;

import java.time.DateTimeException;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 埋点看板「按天」切分用的时区（趋势图、错误列表）：租户未单独配置时用 {@link #defaultZone}（Asia/Shanghai）。
 * 「今天」从该时区的 0 点开始。租户表暂无时区字段，先用配置项按租户编号覆盖：
 * <pre>
 * mugsun.track.timezone:
 *   default-zone: Asia/Shanghai
 *   tenants:
 *     "[000000]": Asia/Shanghai
 * </pre>
 * 键要加中括号和引号，否则 Spring 会改写键名。无效时区回落默认并 WARN。
 */
@Component
@ConfigurationProperties(prefix = "mugsun.track.timezone")
public class TrackTimezoneProperties {

	private static final Logger log = LoggerFactory.getLogger(TrackTimezoneProperties.class);

	private String defaultZone = "Asia/Shanghai";
	private Map<String, String> tenants = new LinkedHashMap<>();

	/** 租户的统计时区；tenant 为 null（超管查看全部）或未配置时用默认时区 */
	public ZoneId zoneFor(String tenant) {
		String configured = tenant == null ? null : tenants.get(tenant);
		if (configured != null && !configured.isBlank()) {
			try {
				return ZoneId.of(configured.trim());
			} catch (DateTimeException e) {
				log.warn("租户 {} 的埋点时区 {} 无效，改用默认 {}", tenant, configured, defaultZone);
			}
		}
		return defaultZoneId();
	}

	private ZoneId defaultZoneId() {
		try {
			return ZoneId.of(defaultZone == null || defaultZone.isBlank() ? "Asia/Shanghai" : defaultZone.trim());
		} catch (DateTimeException e) {
			log.warn("埋点默认时区 {} 无效，改用 Asia/Shanghai", defaultZone);
			return ZoneId.of("Asia/Shanghai");
		}
	}

	public String getDefaultZone() {
		return defaultZone;
	}

	public void setDefaultZone(String defaultZone) {
		this.defaultZone = defaultZone;
	}

	public Map<String, String> getTenants() {
		return tenants;
	}

	public void setTenants(Map<String, String> tenants) {
		this.tenants = tenants == null ? new LinkedHashMap<>() : tenants;
	}
}
