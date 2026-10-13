package com.mugsun.boot.captcha;

import com.mugsun.boot.log.UsualLogService;
import org.springframework.stereotype.Component;

/** 锁定事件写入通用日志（不含轨迹与 token） */
@Component
public class UsualLogCaptchaAudit implements CaptchaAuditSink {

	private final UsualLogService usualLogService;

	public UsualLogCaptchaAudit(UsualLogService usualLogService) {
		this.usualLogService = usualLogService;
	}

	@Override
	public void locked(String ip, String inputMode, String tenantId, long lockSeconds) {
		try {
			usualLogService.record("warn", "captcha",
				"滑块验证连续失败已锁定：ip=" + ip + " mode=" + inputMode + " " + lockSeconds + "s", tenantId);
		} catch (RuntimeException ignored) {
			// 审计失败不影响主流程
		}
	}
}
