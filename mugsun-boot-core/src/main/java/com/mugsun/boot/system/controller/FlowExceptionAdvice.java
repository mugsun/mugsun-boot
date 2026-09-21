package com.mugsun.boot.system.controller;

import com.mugsun.core.tool.api.R;
import org.dromara.warm.flow.core.exception.FlowException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * warm-flow 引擎业务异常处理：把引擎级业务拒绝（越权办理、非发起人撤回、无法跳转等）
 * 映射为友好的业务失败响应并透传引擎提示，避免落入全局 500「系统未捕获异常」污染错误监控。
 */
@RestControllerAdvice
public class FlowExceptionAdvice {

	private static final Logger log = LoggerFactory.getLogger(FlowExceptionAdvice.class);

	@ExceptionHandler(FlowException.class)
	public R<Void> handleFlow(FlowException e) {
		log.warn("工作流业务拒绝：{}", e.getMessage());
		return R.fail(e.getMessage());
	}
}
