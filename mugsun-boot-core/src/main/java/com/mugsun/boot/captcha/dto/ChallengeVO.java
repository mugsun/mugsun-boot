package com.mugsun.boot.captcha.dto;

/** 挑战：不含答案；拼图块为与底图等高的透明图，不暴露纵坐标 */
public record ChallengeVO(String captchaId, String type, String background, String piece,
						  int bgWidth, int bgHeight, int pieceWidth, int pieceHeight, long expireSeconds) {
}
