package com.mugsun.boot.track;

/**
 * 空间行为分析限流：由 {@link TrackSpatialExceptionAdvice} 转成 HTTP 429。
 */
public class TrackSpatialRateLimitException extends RuntimeException {

	public TrackSpatialRateLimitException(String message) {
		super(message);
	}
}
