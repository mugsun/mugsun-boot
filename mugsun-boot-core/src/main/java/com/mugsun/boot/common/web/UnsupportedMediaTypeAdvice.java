package com.mugsun.boot.common.web;

import com.mugsun.core.tool.api.R;
import com.mugsun.core.tool.api.ResultCode;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Content-Type 不匹配（如 CSP 收集端只收 csp-report / reports+json）→ 415。
 * core 的 {@code GlobalExceptionHandler} 兜底会把未声明的异常记成 500；本 advice 优先于兜底，
 * 避免客户端错 Content-Type 被当成服务器故障刷错误日志。
 */
@RestControllerAdvice
@Order(Ordered.HIGHEST_PRECEDENCE)
public class UnsupportedMediaTypeAdvice {

	@ExceptionHandler(HttpMediaTypeNotSupportedException.class)
	public ResponseEntity<R<Void>> handle(HttpMediaTypeNotSupportedException e) {
		return ResponseEntity.status(HttpStatus.UNSUPPORTED_MEDIA_TYPE)
			.body(R.fail(ResultCode.FAILURE, "不支持的 Content-Type"));
	}
}
