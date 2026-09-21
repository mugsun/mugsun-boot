package com.mugsun.boot.track;

/**
 * collect 端点拒收信号：携带 HTTP 状态码（400 协议/应用非法，413 超限，429 限流），
 * 由控制器映射为同状态码的 R 信封响应。
 */
public class TrackCollectException extends RuntimeException {

	private final int status;

	public TrackCollectException(int status, String message) {
		super(message);
		this.status = status;
	}

	public int getStatus() {
		return status;
	}
}
