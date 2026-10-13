package com.mugsun.boot.captcha.dto;

/** 轨迹点：x/y 为相对按下点的显示像素，t 为相对 startTime 的毫秒 */
public record TrackPoint(Double x, Double y, Long t) {
}
