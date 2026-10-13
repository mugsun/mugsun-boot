package com.mugsun.boot.captcha.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;
import java.util.Map;

/**
 * 挑战：不含答案。公共字段 + 按 type 的附加字段（未用到的为 null，不输出）。
 * SLIDER：piece / pieceWidth / pieceHeight / keyboardStep（像素）；
 * ROTATE：piece（已旋转圆图）/ pieceSize / keyboardStep（度）；
 * WORD_CLICK / ICON_CLICK：promptImage / prompt 或 promptNames / count；
 * JIGSAW：grid / tiles / tileWidth / tileHeight。
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ChallengeVO(String captchaId, String type, String inputMode, String background, String piece,
						  int bgWidth, int bgHeight, Integer pieceWidth, Integer pieceHeight, Integer keyboardStep,
						  long expireSeconds, Integer pieceSize, List<String> prompt, List<String> promptNames,
						  String promptImage, Integer count, Map<String, Integer> grid, List<String> tiles,
						  Integer tileWidth, Integer tileHeight) {

	/** SLIDER（v0.5 形状） */
	public ChallengeVO(String captchaId, String type, String inputMode, String background, String piece,
					   int bgWidth, int bgHeight, int pieceWidth, int pieceHeight, Integer keyboardStep,
					   long expireSeconds) {
		this(captchaId, type, inputMode, background, piece, bgWidth, bgHeight, pieceWidth, pieceHeight, keyboardStep,
			expireSeconds, null, null, null, null, null, null, null, null, null);
	}
}
