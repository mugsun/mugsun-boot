package com.mugsun.boot.captcha.dto;

/**
 * 取挑战。inputMode 在出题时声明（pointer | keyboard，缺省 pointer）并写入挑战记录，verify 时必须一致。
 * type 字段即使传入也忽略（类型只由服务端配置决定）。
 */
public record ChallengeRequest(String scene, String clientId, String tenantId, String inputMode, String type) {
}
