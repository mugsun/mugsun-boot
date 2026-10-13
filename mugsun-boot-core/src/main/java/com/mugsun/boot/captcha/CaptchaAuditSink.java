package com.mugsun.boot.captcha;

/** 审计出口：只记 IP/模式/租户，不记轨迹与 token */
public interface CaptchaAuditSink {

	void locked(String ip, String inputMode, String tenantId, long lockSeconds);

	/** 短信下发失败（计数已扣、不回退） */
	default void smsSendFailed(String ip, String phoneHash, String reason) {
	}

	CaptchaAuditSink NOOP = (ip, mode, tenant, sec) -> { };
}
