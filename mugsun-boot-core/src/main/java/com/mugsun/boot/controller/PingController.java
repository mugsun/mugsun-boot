package com.mugsun.boot.controller;

import com.mugsun.core.i18n.message.I18n;
import com.mugsun.core.tool.api.R;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 探活接口（验证 core-tool 的统一响应被正确消费）
 */
@RestController
public class PingController {

	@GetMapping("/ping")
	public R<String> ping() {
		return R.data(I18n.get("mugsun-boot 运行中，thread={0}", Thread.currentThread()));
	}
}
