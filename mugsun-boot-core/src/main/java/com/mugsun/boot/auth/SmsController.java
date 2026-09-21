package com.mugsun.boot.auth;

import com.mugsun.boot.system.service.SmsService;
import com.mugsun.core.tool.api.R;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 短信验证码：发送 / 校验（登录前可用，无需鉴权）
 */
@RestController
@RequestMapping("/auth/sms")
public class SmsController {

	private final SmsService smsService;

	public SmsController(SmsService smsService) {
		this.smsService = smsService;
	}

	@PostMapping("/send")
	public R<Void> send(@RequestParam String phone) {
		smsService.sendCode(phone);
		return R.success("验证码已发送");
	}

	/** 校验端点已下线：布尔 oracle 可被在线爆破利用（验证码校验只发生在 sms-login 闭环内） */
}
