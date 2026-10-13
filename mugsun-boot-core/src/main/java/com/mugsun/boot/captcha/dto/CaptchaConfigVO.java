package com.mugsun.boot.captcha.dto;

/** 前端提示用；业务接口仍按服务端解析的真实租户/客户端重新判定 */
public record CaptchaConfigVO(boolean enabled, String type, String scene) {
}
