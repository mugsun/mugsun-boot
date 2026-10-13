package com.mugsun.boot.captcha;

import com.mugsun.core.tool.api.R;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.Map;

/** 验证码异常 → HTTP 200 + R.code=4001x；先于全局 ServiceException 处理器生效 */
@RestControllerAdvice
@Order(Ordered.HIGHEST_PRECEDENCE)
public class CaptchaExceptionAdvice {

	@ExceptionHandler(CaptchaException.class)
	public R<Map<String, Object>> handle(CaptchaException e) {
		R<Map<String, Object>> r = R.fail(e.getMessage());
		r.setCode(e.getCode());
		r.setData(e.getData());
		return r;
	}
}
