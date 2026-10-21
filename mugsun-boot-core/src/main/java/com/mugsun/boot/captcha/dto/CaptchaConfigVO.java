package com.mugsun.boot.captcha.dto;

/**
 * 前端提示用；业务接口仍按服务端解析的真实租户/客户端重新判定。
 * registerMode：自助注册模式 OFF/OPEN/APPROVAL，登录页据此隐藏注册入口（与 scene 无关，任何 scene 都返回）。
 */
public record CaptchaConfigVO(boolean enabled, String type, String scene, String registerMode) {
}
