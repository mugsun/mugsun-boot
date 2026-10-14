package com.mugsun.boot.log;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.util.AntPathMatcher;

import java.util.ArrayList;
import java.util.List;

/**
 * 操作日志（自动留痕）配置。
 * <p>{@code exclude-paths}：不进自动留痕的写接口路径（Ant 风格，按去掉 context-path 后的路径匹配）。
 * 默认排除埋点 SDK 的上报接口 {@code /track/collect}、{@code /track/replay}：它们由浏览器高频调用，
 * 不是管理操作，留痕只会刷屏并白白参与哈希链计算。
 * <p>只影响自动留痕；显式标了 {@link OperationLog} 的方法照常记录。
 * 覆盖时整体替换列表（Spring 列表绑定规则），要保留默认项请一并写上。
 */
@Component
@ConfigurationProperties(prefix = "mugsun.oper-log")
public class OperLogProperties {

	public static final List<String> DEFAULT_EXCLUDE_PATHS = List.of("/track/collect", "/track/replay", "/track/api-body");

	private static final AntPathMatcher MATCHER = new AntPathMatcher();

	private List<String> excludePaths = new ArrayList<>(DEFAULT_EXCLUDE_PATHS);

	public List<String> getExcludePaths() {
		return excludePaths;
	}

	public void setExcludePaths(List<String> excludePaths) {
		this.excludePaths = excludePaths == null ? new ArrayList<>() : excludePaths;
	}

	/** path 为去掉 context-path 后的请求路径 */
	public boolean isExcluded(String path) {
		if (path == null || excludePaths.isEmpty()) {
			return false;
		}
		String p = path.length() > 1 && path.endsWith("/") ? path.substring(0, path.length() - 1) : path;
		for (String pattern : excludePaths) {
			if (pattern != null && !pattern.isBlank() && MATCHER.match(pattern.trim(), p)) {
				return true;
			}
		}
		return false;
	}
}
