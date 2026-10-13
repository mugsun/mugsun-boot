package com.mugsun.boot.captcha.dto;

import java.util.List;

/**
 * 校验请求。inputMode=pointer（默认）校验落点+时长+轨迹；inputMode=keyboard 校验落点+时长+按键次数。
 * answer.x：拼图块左边缘在显示底图中的 x（显示像素）。
 */
public record VerifyRequest(String captchaId, String inputMode, Integer bgDisplayWidth,
							Long startTime, Long endTime, List<TrackPoint> track,
							Answer answer, Integer keyPresses) {

	public record Answer(Double x) {
	}
}
