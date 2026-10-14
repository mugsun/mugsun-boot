package com.mugsun.boot.captcha.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;
import java.util.Map;

/**
 * 挑战：不含答案。公共字段 + 按 type 的附加字段（未用到的为 null，不输出）。
 * SLIDER：piece / pieceWidth / pieceHeight / keyboardStep（像素）；
 * ROTATE：piece（已旋转圆图）/ pieceSize / keyboardStep（度）；
 * WORD_CLICK：prompt（整词字符串，3–4 字，按字序依次点）/ count（需要点的字数 3–4）/ hasDecoy（画面含 1–2 个干扰字，恒为 true）/ promptImage / itemSize；
 * ICON_CLICK：promptNames / promptImage / count / itemSize（字号或图标边长，原始像素，名义值 ±3）；
 * JIGSAW：grid / tiles（JPEG）/ tileWidth / tileHeight；无 background 字段。
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ChallengeVO(String captchaId, String type, String inputMode, String background, String piece,
						  int bgWidth, int bgHeight, Integer pieceWidth, Integer pieceHeight, Integer keyboardStep,
						  long expireSeconds, Integer pieceSize, String prompt, List<String> promptNames,
						  String promptImage, Integer count, Map<String, Integer> grid, List<String> tiles,
						  Integer tileWidth, Integer tileHeight, Integer itemSize, Boolean hasDecoy) {

	/** SLIDER（v0.5 形状） */
	public ChallengeVO(String captchaId, String type, String inputMode, String background, String piece,
					   int bgWidth, int bgHeight, int pieceWidth, int pieceHeight, Integer keyboardStep,
					   long expireSeconds) {
		this(captchaId, type, inputMode, background, piece, bgWidth, bgHeight, pieceWidth, pieceHeight, keyboardStep,
			expireSeconds, null, null, null, null, null, null, null, null, null, null, null);
	}
}
