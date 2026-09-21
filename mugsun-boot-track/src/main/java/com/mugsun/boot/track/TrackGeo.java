package com.mugsun.boot.track;

import com.fasterxml.jackson.databind.JsonNode;
import com.mugsun.boot.common.constant.TrackConstants;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * 埋点地理口径：坐标校验圆整 + IP 属地省份归一（G106）。
 * <p>坐标只信服务端圆整后的值——客户端上报可伪造，但非法范围/非数字一律丢列，不拒整事件。
 */
public final class TrackGeo {

	private TrackGeo() {
	}

	/**
	 * 从 props 提取一对合法 WGS84 坐标并圆整到 {@link TrackConstants#GEO_SCALE} 位。
	 * 合法键：{@code geo_lon}/{@code geo_lat}、{@code $geo_lon}/{@code $geo_lat}、
	 * {@code longitude}/{@code latitude}、{@code lon}/{@code lat}，以及嵌套 {@code geo.{lon,lng,lat}}。
	 *
	 * @return {@code [lon, lat]}；缺字段或越界返回 {@code null}
	 */
	public static double[] parseLonLat(JsonNode props) {
		if (props == null || !props.isObject()) {
			return null;
		}
		Double lon = firstNumber(props, TrackConstants.PROP_GEO_LON, TrackConstants.PROP_GEO_LON_ALT,
			"longitude", "lon");
		Double lat = firstNumber(props, TrackConstants.PROP_GEO_LAT, TrackConstants.PROP_GEO_LAT_ALT,
			"latitude", "lat");
		JsonNode nested = props.get("geo");
		if (nested != null && nested.isObject()) {
			if (lon == null) {
				lon = firstNumber(nested, "lon", "lng", "longitude");
			}
			if (lat == null) {
				lat = firstNumber(nested, "lat", "latitude");
			}
		}
		return clamp(lon, lat);
	}

	/** 越界/非有限返回 null；否则圆整到 4 位小数。 */
	public static double[] clamp(Double lon, Double lat) {
		if (lon == null || lat == null || !Double.isFinite(lon) || !Double.isFinite(lat)) {
			return null;
		}
		if (lon < -180d || lon > 180d || lat < -90d || lat > 90d) {
			return null;
		}
		return new double[] { round(lon), round(lat) };
	}

	public static double round(double value) {
		return BigDecimal.valueOf(value).setScale(TrackConstants.GEO_SCALE, RoundingMode.HALF_UP).doubleValue();
	}

	/**
	 * ip2region 形如 {@code 国家|区域|省份|城市|ISP}：取省份；省份空/0 退城市；再退国家；
	 * 内网、空串归 {@link TrackConstants#GEO_REGION_UNKNOWN} / {@link TrackConstants#GEO_REGION_INTRANET}。
	 */
	public static String regionLabel(String ipRegion) {
		if (ipRegion == null || ipRegion.isBlank()) {
			return TrackConstants.GEO_REGION_UNKNOWN;
		}
		String raw = ipRegion.trim();
		if (raw.contains("内网")) {
			return TrackConstants.GEO_REGION_INTRANET;
		}
		String[] parts = raw.split("\\|", -1);
		String province = part(parts, 2);
		if (usable(province)) {
			return province;
		}
		String city = part(parts, 3);
		if (usable(city)) {
			return city;
		}
		String country = part(parts, 0);
		if (usable(country)) {
			return country;
		}
		return TrackConstants.GEO_REGION_UNKNOWN;
	}

	private static String part(String[] parts, int index) {
		return index >= 0 && index < parts.length ? parts[index].trim() : "";
	}

	private static boolean usable(String value) {
		return !value.isEmpty() && !"0".equals(value) && !"null".equalsIgnoreCase(value);
	}

	private static Double firstNumber(JsonNode obj, String... keys) {
		for (String key : keys) {
			JsonNode n = obj.get(key);
			if (n == null || n.isNull() || n.isMissingNode()) {
				continue;
			}
			if (n.isNumber()) {
				return n.doubleValue();
			}
			if (n.isTextual()) {
				try {
					return Double.parseDouble(n.asText().trim());
				} catch (NumberFormatException ignored) {
					// 下一项
				}
			}
		}
		return null;
	}
}
