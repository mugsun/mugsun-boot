package com.mugsun.boot.common.web;

import com.mugsun.core.tool.api.R;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.multipart.MaxUploadSizeExceededException;

/** 容器按 spring.servlet.multipart 上限截断的上传 → HTTP 400 + 友好提示 */
@RestControllerAdvice
@Order(Ordered.HIGHEST_PRECEDENCE)
public class UploadSizeExceptionAdvice {

	@ExceptionHandler(MaxUploadSizeExceededException.class)
	public ResponseEntity<R<Void>> handle(MaxUploadSizeExceededException e) {
		R<Void> r = R.fail("文件超过 20MB。请压缩或拆分后再上传");
		r.setCode(HttpStatus.BAD_REQUEST.value());
		return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(r);
	}
}
