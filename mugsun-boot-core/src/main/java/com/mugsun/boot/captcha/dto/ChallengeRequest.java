package com.mugsun.boot.captcha.dto;

/** 取挑战：type 字段即使传入也忽略（类型只由服务端配置决定） */
public record ChallengeRequest(String scene, String clientId, String tenantId, String type) {
}
