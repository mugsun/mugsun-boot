package com.mugsun.boot.security;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * multipart 白名单（{@link MultipartAuthFilter}）：只有这里列出的 servletPath 允许 multipart，且必须已登录。
 * <p>按规范化后的 servletPath 精确比对（区分大小写，不支持通配）。新增上传接口时把路径追加到
 * {@code mugsun.security.multipart.allowed-paths}；启动时 {@link MultipartEndpointAudit} 会对照
 * 带 MultipartFile 参数的接口，漏配或多配都会打 WARN。
 * <p>注意：在 yml 里写这个列表是整体替换，要把默认的 5 条一起写上。
 */
@Component
@ConfigurationProperties(prefix = "mugsun.security.multipart")
public class MultipartGuardProperties {

	/** 默认 5 个上传接口：附件、头像、用户导入、行政区导入、sourcemap */
	public static final List<String> DEFAULT_ALLOWED_PATHS = List.of(
		"/system/file/upload",
		"/auth/avatar/upload",
		"/system/user/import",
		"/system/region/import",
		"/system/track/sourcemap/upload");

	private List<String> allowedPaths = new ArrayList<>(DEFAULT_ALLOWED_PATHS);

	public List<String> getAllowedPaths() {
		return allowedPaths;
	}

	public void setAllowedPaths(List<String> allowedPaths) {
		this.allowedPaths = allowedPaths == null ? new ArrayList<>() : allowedPaths;
	}
}
