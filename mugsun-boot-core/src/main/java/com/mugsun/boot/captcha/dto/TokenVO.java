package com.mugsun.boot.captcha.dto;

public record TokenVO(String captchaToken, long expireSeconds) {
}
