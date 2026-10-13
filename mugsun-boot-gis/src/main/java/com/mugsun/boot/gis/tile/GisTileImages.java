package com.mugsun.boot.gis.tile;

import org.springframework.http.MediaType;

import java.util.Locale;

/**
 * 瓦片响应类型白名单：只放行 png / jpeg / webp / gif，且声明类型必须与文件头一致。
 * svg（可内嵌脚本）、html、json 等一律拒绝，防止上游借瓦片代理向同源投递可执行内容。
 */
public final class GisTileImages {

	public static final MediaType WEBP = MediaType.parseMediaType("image/webp");

	private GisTileImages() {
	}

	/** @return 规范化后的类型；不在白名单或文件头不符返回 null */
	public static MediaType accept(String contentType, byte[] body) {
		if (contentType == null || body == null || body.length < 12) {
			return null;
		}
		String t = contentType.split(";")[0].trim().toLowerCase(Locale.ROOT);
		return switch (t) {
			case "image/png" -> isPng(body) ? MediaType.IMAGE_PNG : null;
			case "image/jpeg", "image/jpg" -> isJpeg(body) ? MediaType.IMAGE_JPEG : null;
			case "image/gif" -> isGif(body) ? MediaType.IMAGE_GIF : null;
			case "image/webp" -> isWebp(body) ? WEBP : null;
			default -> null;
		};
	}

	static boolean isPng(byte[] b) {
		return (b[0] & 0xff) == 0x89 && b[1] == 'P' && b[2] == 'N' && b[3] == 'G';
	}

	static boolean isJpeg(byte[] b) {
		return (b[0] & 0xff) == 0xFF && (b[1] & 0xff) == 0xD8 && (b[2] & 0xff) == 0xFF;
	}

	static boolean isGif(byte[] b) {
		return b[0] == 'G' && b[1] == 'I' && b[2] == 'F' && b[3] == '8';
	}

	static boolean isWebp(byte[] b) {
		return b[0] == 'R' && b[1] == 'I' && b[2] == 'F' && b[3] == 'F'
			&& b[8] == 'W' && b[9] == 'E' && b[10] == 'B' && b[11] == 'P';
	}
}
