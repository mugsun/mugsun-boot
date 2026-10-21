package com.mugsun.boot.auth.register;

import com.mugsun.core.tool.api.R;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/** 注册限流 → HTTP 429 + R.code=429 */
@RestControllerAdvice
@Order(Ordered.HIGHEST_PRECEDENCE)
public class RegisterExceptionAdvice {

	@ExceptionHandler(RegisterRateLimitException.class)
	public ResponseEntity<R<Void>> handle(RegisterRateLimitException e) {
		R<Void> r = R.fail(e.getMessage());
		r.setCode(HttpStatus.TOO_MANY_REQUESTS.value());
		return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS).header("Retry-After", "3600").body(r);
	}
}
