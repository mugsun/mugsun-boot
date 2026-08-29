package com.mugsun.boot.gis;

import com.mugsun.core.tool.exception.ServiceException;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 内置示例数据：不占图层库、不绑租户。每个 code 对应示例中心一页。
 */
@Component
public class GisDemoCatalog {

	public static final String POI = "poi";
	public static final String HEAT = "heat";
	public static final String CLUSTER = "cluster";
	public static final String TRACK = "track";
	public static final String PLAYBACK = "playback";
	public static final String FENCE = "fence";
	public static final String BUFFER = "buffer";
	public static final String RADIUS = "radius";
	public static final String GEOCODE = "geocode";
	public static final String MEASURE = "measure";
	public static final String TILES3D = "tiles3d";

	private final GisFormatService formatService;

	public GisDemoCatalog(GisFormatService formatService) {
		this.formatService = formatService;
	}

	public List<Map<String, Object>> list() {
		List<Map<String, Object>> out = new ArrayList<>();
		out.add(meta(POI, "兴趣点", "标注 + 点选看属性，开放平台最常见的落点", "cover", "overlay", GisConstants.KIND_VECTOR, 8));
		out.add(meta(HEAT, "热力图", "密度渲染，门店/客流/告警常用", "cover", "heatmap", GisConstants.KIND_HEATMAP, 48));
		out.add(meta(CLUSTER, "点聚合", "大量点缩放到散开，避免扎堆", "cover", "cluster", GisConstants.KIND_VECTOR, 48));
		out.add(meta(PLAYBACK, "轨迹回放", "播放 / 暂停 / 倍速 / 拖进度，物流与巡检标配", "motion", "playback", GisConstants.KIND_VECTOR, 1));
		out.add(meta(FENCE, "电子围栏", "多边形围栏，可算面积、做进出判断", "motion", "overlay", GisConstants.KIND_VECTOR, 1));
		out.add(meta(BUFFER, "缓冲分析", "服务端 JTS 按米缓冲出面", "motion", "buffer", GisConstants.KIND_VECTOR, 1));
		out.add(meta(RADIUS, "圈选查询", "点一下定圆心，列出半径内的点", "query", "radius", GisConstants.KIND_VECTOR, 48));
		out.add(meta(GEOCODE, "点选拾取", "单击地图逆地理，得到地址", "query", "geocode", GisConstants.KIND_VECTOR, 0));
		out.add(meta(MEASURE, "测距测面", "折线长度、多边形面积，工单勘察常用", "query", "measure", GisConstants.KIND_VECTOR, 0));
		out.add(meta(TILES3D, "三维切片", "倾斜摄影 / 实景模型走 3D Tiles，点楼看属性；示例切片随包发布",
			"scene", "tileset", GisConstants.KIND_3DTILES, 16));
		return out;
	}

	public Map<String, Object> collection(String code) {
		return switch (code == null ? "" : code) {
			case POI -> formatService.normalizeUnknown(poi());
			case HEAT, CLUSTER, RADIUS -> formatService.normalizeUnknown(stores());
			case TRACK, PLAYBACK -> formatService.normalizeUnknown(track());
			case FENCE -> formatService.normalizeUnknown(fence());
			case BUFFER -> formatService.normalizeUnknown(List.of(point(116.397428, 39.90923, "天安门", "point")));
			case GEOCODE, MEASURE -> emptyCollection();
			case TILES3D -> tileset3d();
			default -> throw new ServiceException(GisConstants.MSG_DEMO_MISSING);
		};
	}

	private static Map<String, Object> meta(String code, String title, String summary, String group, String ui,
			String kind, int count) {
		Map<String, Object> row = new LinkedHashMap<>();
		row.put("code", code);
		row.put("title", title);
		row.put("summary", summary);
		row.put("group", group);
		row.put("ui", ui);
		row.put("kind", kind);
		row.put("count", count);
		return row;
	}

	/**
	 * 三维示例不是要素集合，只给切片入口与渲染参数；相机由前端按切片包围球自动构图，
	 * 免得写死的经纬高和切片位置对不上。
	 */
	private static Map<String, Object> tileset3d() {
		Map<String, Object> out = new LinkedHashMap<>();
		out.put("mugsunGis", GisConstants.SPEC_VERSION);
		out.put("crs", GisConstants.CRS_WGS84);
		out.put("type", "Tileset3D");
		out.put("hosted", GisConstants.TILESET_DEMO_CITY);
		out.put("url", Gis3dTilesSpec.HOSTED_PREFIX + GisConstants.TILESET_DEMO_CITY);
		out.put("maximumScreenSpaceError", 16);
		out.put("heightOffset", 0);
		out.put("count", 16);
		return out;
	}

	private static Map<String, Object> emptyCollection() {
		Map<String, Object> out = new LinkedHashMap<>();
		out.put("mugsunGis", GisConstants.SPEC_VERSION);
		out.put("crs", GisConstants.CRS_WGS84);
		out.put("type", "FeatureCollection");
		out.put("count", 0);
		out.put("features", List.of());
		return out;
	}

	private static List<Map<String, Object>> poi() {
		return List.of(
			point(116.397428, 39.90923, "天安门", "point"),
			point(116.3970, 39.9180, "故宫", "point"),
			point(116.3964, 39.9253, "景山公园", "point"),
			point(116.4115, 39.9139, "王府井", "point"),
			point(116.3891, 39.9254, "北海公园", "point"),
			point(116.3837, 39.9033, "国家大剧院", "point"),
			point(116.3979, 39.8991, "前门大街", "point"),
			point(116.3859, 39.9372, "什刹海", "point")
		);
	}

	private static List<Map<String, Object>> stores() {
		List<Map<String, Object>> out = new ArrayList<>();
		for (int i = 0; i < 48; i++) {
			double lon = 116.455 + (i % 8) * 0.007;
			double lat = 39.908 + (i / 8) * 0.005;
			out.add(point(lon, lat, "门店-" + String.format("%02d", i + 1), "point"));
		}
		return out;
	}

	private static Map<String, Object> track() {
		List<List<Double>> line = List.of(
			List.of(116.352, 39.9078),
			List.of(116.361, 39.9077),
			List.of(116.371, 39.9076),
			List.of(116.381, 39.90755),
			List.of(116.390, 39.9075),
			List.of(116.3974, 39.9074),
			List.of(116.405, 39.9076),
			List.of(116.411, 39.9080),
			List.of(116.428, 39.9084),
			List.of(116.445, 39.9088)
		);
		List<Integer> times = List.of(0, 5, 10, 16, 21, 27, 32, 36, 42, 48);
		Map<String, Object> geom = new LinkedHashMap<>();
		geom.put("type", "LineString");
		geom.put("coordinates", line);
		Map<String, Object> props = new LinkedHashMap<>();
		props.put("name", "京A·巡检 01");
		props.put("kind", "line");
		props.put("bizId", "track-ca");
		props.put("times", times);
		props.put("durationSec", 48);
		props.put("plate", "京A·巡检 01");
		Map<String, Object> feat = new LinkedHashMap<>();
		feat.put("type", "Feature");
		feat.put("properties", props);
		feat.put("geometry", geom);
		return feat;
	}

	private static Map<String, Object> fence() {
		List<List<List<Double>>> rings = List.of(List.of(
			List.of(116.372, 39.898),
			List.of(116.428, 39.898),
			List.of(116.428, 39.928),
			List.of(116.372, 39.928),
			List.of(116.372, 39.898)
		));
		Map<String, Object> geom = new LinkedHashMap<>();
		geom.put("type", "Polygon");
		geom.put("coordinates", rings);
		Map<String, Object> props = new LinkedHashMap<>();
		props.put("name", "核心区围栏");
		props.put("kind", "polygon");
		props.put("bizId", "fence-core");
		Map<String, Object> feat = new LinkedHashMap<>();
		feat.put("type", "Feature");
		feat.put("properties", props);
		feat.put("geometry", geom);
		return feat;
	}

	private static Map<String, Object> point(double lon, double lat, String name, String kind) {
		Map<String, Object> geom = new LinkedHashMap<>();
		geom.put("type", "Point");
		geom.put("coordinates", List.of(lon, lat));
		Map<String, Object> props = new LinkedHashMap<>();
		props.put("name", name);
		props.put("kind", kind);
		props.put("title", name);
		props.put("bizId", "demo-" + name);
		Map<String, Object> feat = new LinkedHashMap<>();
		feat.put("type", "Feature");
		feat.put("properties", props);
		feat.put("geometry", geom);
		return feat;
	}
}
