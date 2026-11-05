package com.mugsun.boot.track;

import com.mugsun.core.tool.api.R;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/** 空间行为分析限流 → HTTP 429 + R.code=429 */
@RestControllerAdvice
@Order(Ordered.HIGHEST_PRECEDENCE)
public class TrackSpatialExceptionAdvice {

	@ExceptionHandler(TrackSpatialRateLimitException.class)
	public ResponseEntity<R<Void>> handle(TrackSpatialRateLimitException e) {
		R<Void> r = R.fail(e.getMessage());
		r.setCode(HttpStatus.TOO_MANY_REQUESTS.value());
		return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS).header("Retry-After", "60").body(r);
	}
}
