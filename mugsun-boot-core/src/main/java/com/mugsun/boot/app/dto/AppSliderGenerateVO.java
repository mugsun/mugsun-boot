package com.mugsun.boot.app.dto;

public record AppSliderGenerateVO(
	String captchaId,
	String backgroundImage,
	String sliderImage,
	int width,
	int height,
	int pieceSize
) {
}
