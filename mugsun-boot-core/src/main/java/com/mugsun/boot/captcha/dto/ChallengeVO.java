package com.mugsun.boot.captcha.dto;

/**
 * 挑战：不含答案；拼图块为与底图等高的透明图，不暴露纵坐标。
 * keyboardStep 仅 inputMode=keyboard 时返回：每次按键拼图块移动的原始像素数（显示时乘 bgDisplayWidth/bgWidth）。
 */
public record ChallengeVO(String captchaId, String type, String inputMode, String background, String piece,
						  int bgWidth, int bgHeight, int pieceWidth, int pieceHeight, Integer keyboardStep,
						  long expireSeconds) {
}
