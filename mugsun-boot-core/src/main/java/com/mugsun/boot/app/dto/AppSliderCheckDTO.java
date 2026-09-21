package com.mugsun.boot.app.dto;

import java.util.List;

public record AppSliderCheckDTO(
	String captchaId,
	int moveX,
	long startTime,
	long endTime,
	List<AppSliderTrackPoint> tracks
) {
}
