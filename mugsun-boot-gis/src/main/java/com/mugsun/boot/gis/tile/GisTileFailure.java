package com.mugsun.boot.gis.tile;

import com.mugsun.boot.gis.GisConstants;
import com.mugsun.boot.gis.xyz.GisXyzGuard;
import com.mugsun.core.tool.exception.ServiceException;

import java.net.http.HttpTimeoutException;

/**
 * 瓦片代理失败原因：决定响应码与 X-Mugsun-Tile-Error 原因头，以及是否打 WARN。
 */
public enum GisTileFailure {

	/** 上游返回非 200 */
	UPSTREAM_STATUS(502, "upstream-status", true),
	/** 上游返回的不是白名单图片（svg、html、类型与文件头不符） */
	BAD_CONTENT_TYPE(502, "bad-content-type", true),
	/** 单张超过大小上限 */
	TOO_LARGE(502, "too-large", true),
	/** 连接或读取超时 */
	TIMEOUT(504, "timeout", true),
	/** 连不上、TLS、DNS 失败等 */
	UPSTREAM_UNREACHABLE(502, "upstream-unreachable", true),
	/** 解析到内网 / 保留 / 默认拦截地址段（198.18/15、NAT64、6to4 等） */
	BLOCKED_ADDRESS(403, "blocked-address", true),
	/** 域名不在白名单、写了 IP 字面量 */
	HOST_NOT_ALLOWED(403, "host-not-allowed", true),
	/** 租户模板不合法 */
	BAD_TEMPLATE(502, "bad-template", true),
	/** 未配置密钥 / 白名单为空 */
	NOT_CONFIGURED(404, "not-configured", false),
	/** 底图已停用 */
	DISABLED(404, "disabled", false),
	/** 超出 maxZoom 或坐标越界：正常现象，不告警 */
	OUT_OF_RANGE(404, "out-of-range", false);

	public static final String HEADER = "X-Mugsun-Tile-Error";

	private final int status;
	private final String code;
	private final boolean warn;

	GisTileFailure(int status, String code, boolean warn) {
		this.status = status;
		this.code = code;
		this.warn = warn;
	}

	public int status() {
		return status;
	}

	public String code() {
		return code;
	}

	public boolean warn() {
		return warn;
	}

	/** 按异常归类；未知异常算上游不可达 */
	public static GisTileFailure of(Throwable e) {
		for (Throwable t = e; t != null; t = t.getCause()) {
			if (t instanceof HttpTimeoutException) {
				return TIMEOUT;
			}
			if (t instanceof ServiceException se) {
				String m = se.getMessage();
				if (GisXyzGuard.MSG_PRIVATE.equals(m)) {
					return BLOCKED_ADDRESS;
				}
				if (GisXyzGuard.MSG_HOST.equals(m) || GisXyzGuard.MSG_IP.equals(m)
					|| GisUpstreamHttpMessages.ILLEGAL_HOST.equals(m)) {
					return HOST_NOT_ALLOWED;
				}
				if (GisUpstreamHttpMessages.TOO_LARGE.equals(m)) {
					return TOO_LARGE;
				}
				if (GisConstants.MSG_MVT_ZXY.equals(m)) {
					return OUT_OF_RANGE;
				}
				if (GisXyzGuard.MSG_TEMPLATE.equals(m) || GisXyzGuard.MSG_SUBDOMAINS.equals(m)) {
					return BAD_TEMPLATE;
				}
			}
		}
		return UPSTREAM_UNREACHABLE;
	}
}
