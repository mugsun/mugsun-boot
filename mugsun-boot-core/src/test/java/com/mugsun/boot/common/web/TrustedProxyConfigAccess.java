package com.mugsun.boot.common.web;

/** 测试辅助：跨包访问 {@link TrustedProxyConfig#unconfigured(String)} */
public final class TrustedProxyConfigAccess {
	private TrustedProxyConfigAccess() {
	}

	public static boolean unconfigured(String raw) {
		return TrustedProxyConfig.unconfigured(raw);
	}
}
