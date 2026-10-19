package com.mugsun.boot.captcha.dto;

import java.util.List;

/**
 * 校验请求。公共字段同 v0.5；answer 按题型取字段：
 * SLIDER x（显示像素）；ROTATE angle（施加的顺时针角度，度）；
 * WORD_CLICK / ICON_CLICK points（按提示顺序，显示像素，原点底图左上角）；JIGSAW order（每格放第几块）。
 */
public record VerifyRequest(String captchaId, String inputMode, Integer bgDisplayWidth,
							Long startTime, Long endTime, List<TrackPoint> track,
							Answer answer, Integer keyPresses) {

	public record Answer(Double x, Double angle, List<Point> points, List<Integer> order) {
		public Answer(Double x) {
			this(x, null, null, null);
		}
	}

	public record Point(Double x, Double y) {
	}
}
