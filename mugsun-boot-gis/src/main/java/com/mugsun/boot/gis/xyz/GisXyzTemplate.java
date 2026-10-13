package com.mugsun.boot.gis.xyz;

import java.util.List;

/**
 * 一份 XYZ 瓦片模板：{z}/{x}/{y} 必填，{s} 按子域轮换，{key} 可选（密钥只在服务端拼）。
 */
public record GisXyzTemplate(String url, List<String> subdomains, String attribution, int maxZoom) {

	public GisXyzTemplate {
		subdomains = subdomains == null ? List.of() : List.copyOf(subdomains);
		attribution = attribution == null ? "" : attribution;
	}

	/** 渲染一张瓦片的上游地址；不做校验，调用方必须先过 {@link GisXyzGuard#checkTemplate} */
	public String render(int z, int x, int y, String key) {
		String out = url.replace("{z}", Integer.toString(z))
			.replace("{x}", Integer.toString(x))
			.replace("{y}", Integer.toString(y));
		if (out.contains("{s}")) {
			String sub = subdomains.isEmpty() ? "" : subdomains.get(Math.floorMod(x + y, subdomains.size()));
			out = out.replace("{s}", sub);
		}
		if (out.contains("{key}")) {
			out = out.replace("{key}", key == null ? "" : java.net.URLEncoder.encode(key, java.nio.charset.StandardCharsets.UTF_8));
		}
		return out;
	}
}
