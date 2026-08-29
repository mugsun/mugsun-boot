package com.mugsun.boot.gis;

import java.util.List;
import java.util.Set;

/**
 * GIS 模块常量：开关键、供应商固定键、权限码、瓦片约束。
 */
public final class GisConstants {

	/** sys_param：模块总开关，默认 true；false 时菜单隐藏、业务接口拒绝 */
	public static final String PARAM_MODULE_ENABLED = "gis.module.enabled";
	/** sys_param：quantized-mesh 地形服务地址，留空则三维只有平地形，前端地形开关置灰 */
	public static final String PARAM_TERRAIN_URL = "gis.terrain.url";

	public static final String PROVIDER_TIANDITU = "tianditu";
	public static final String PROVIDER_AMAP = "amap";
	public static final String PROVIDER_BAIDU = "baidu";
	public static final String PROVIDER_GOOGLE = "google";

	public static final List<String> PROVIDERS = List.of(
		PROVIDER_TIANDITU, PROVIDER_AMAP, PROVIDER_BAIDU, PROVIDER_GOOGLE);

	/** 底图样式：矢量 / 影像 / 影像注记 / 矢量注记 */
	public static final String STYLE_VEC = "vec";
	public static final String STYLE_IMG = "img";
	public static final String STYLE_IMG_LABEL = "img_label";
	public static final String STYLE_VEC_LABEL = "vec_label";

	public static final Set<String> STYLES = Set.of(STYLE_VEC, STYLE_IMG, STYLE_IMG_LABEL, STYLE_VEC_LABEL);

	public static final String PERM_WORKSPACE = "gis:workspace:list";
	public static final String PERM_PROVIDER_LIST = "gis:provider:list";
	public static final String PERM_PROVIDER_SAVE = "gis:provider:save";
	public static final String PERM_PROVIDER_REMOVE = "gis:provider:remove";
	public static final String PERM_SCENE_SAVE = "gis:scene:save";
	public static final String PERM_SCENE_REMOVE = "gis:scene:remove";
	public static final String PERM_LAYER_LIST = "gis:layer:list";
	public static final String PERM_LAYER_SAVE = "gis:layer:save";
	public static final String PERM_LAYER_REMOVE = "gis:layer:remove";
	public static final String PERM_SCENE_LIST = "gis:scene:list";
	public static final String PERM_ANALYZE = "gis:analyze:run";
	public static final String PERM_DEMO = "gis:demo:list";

	public static final int SPEC_VERSION = 1;
	public static final String CRS_WGS84 = "EPSG:4326";
	public static final String KIND_VECTOR = "vector";
	public static final String KIND_HEATMAP = "heatmap";
	public static final String KIND_XYZ = "xyz";
	public static final String KIND_WMS = "wms";
	/** 三维切片图层：倾斜摄影 / 模型，数据是 3D Tiles 的 tileset.json */
	public static final String KIND_3DTILES = "3dtiles";
	public static final int FEATURE_MAX = 8000;

	/** 随包发布的示例切片目录（classpath:gis/tileset/<code>），只允许白名单内的 code */
	public static final String TILESET_DEMO_CITY = "demo-city";
	public static final Set<String> TILESETS = Set.of(TILESET_DEMO_CITY);
	public static final String TILESET_ROOT = "gis/tileset/";

	public static final String OP_BUFFER = "buffer";
	public static final String OP_CENTROID = "centroid";
	public static final String OP_BBOX = "bbox";
	public static final String OP_AREA = "area";
	public static final String OP_LENGTH = "length";
	public static final String OP_DISTANCE = "distance";
	public static final String OP_INTERSECTS = "intersects";
	public static final String OP_CONTAINS = "contains";
	public static final String OP_UNION = "union";
	public static final String OP_DIFFERENCE = "difference";
	public static final String OP_SIMPLIFY = "simplify";
	public static final String OP_CONVEX_HULL = "convexHull";

	public static final java.util.Set<String> ANALYZE_OPS = java.util.Set.of(
		OP_BUFFER, OP_CENTROID, OP_BBOX, OP_AREA, OP_LENGTH, OP_DISTANCE,
		OP_INTERSECTS, OP_CONTAINS, OP_UNION, OP_DIFFERENCE, OP_SIMPLIFY, OP_CONVEX_HULL);

	public static final double BUFFER_DEFAULT_M = 500d;
	public static final double SIMPLIFY_DEFAULT = 0.0001d;
	public static final int BUFFER_MAX_M = 200_000;

	/** 空间查询：一次最多回多少要素，防止把整层捞成一个响应 */
	public static final int SPATIAL_LIMIT_DEFAULT = 1000;
	public static final int SPATIAL_LIMIT_MAX = 5000;
	/** 半径查询上限，与缓冲一致 */
	public static final int RADIUS_MAX_M = BUFFER_MAX_M;
	/** 下沉数据库执行 / 回落 Java 内存执行，随查询结果一起回前端，便于定位性能问题 */
	public static final String ENGINE_POSTGIS = "postgis";
	public static final String ENGINE_JAVA = "java";
	/** 矢量瓦片：MVT 坐标范围与缓冲，取 Mapbox 默认值 */
	public static final int MVT_EXTENT = 4096;
	public static final int MVT_BUFFER = 64;
	public static final String MVT_LAYER_NAME = "features";
	public static final String MVT_CONTENT_TYPE = "application/vnd.mapbox-vector-tile";

	public static final int STATUS_ENABLE = 1;
	public static final int STATUS_DISABLE = 0;

	public static final int TILE_MIN_Z = 1;
	public static final int TILE_MAX_Z = 18;

	public static final String MSG_DISABLED = "地理信息模块未启用";
	public static final String MSG_PROVIDER_UNKNOWN = "未知底图供应商";
	public static final String MSG_PROVIDER_KEY = "请先配置该底图的访问密钥";
	public static final String MSG_SCENE_MISSING = "场景不存在";
	public static final String MSG_JSON_INVALID = "场景配置不是合法 JSON";
	public static final String MSG_SEARCH_NO_KEY = "请先配置并启用底图密钥后再使用地名搜索";
	public static final String MSG_SEARCH_PROVIDER = "请指定底图供应商";
	public static final String MSG_SEARCH_KEYWORD = "请输入 2～64 个字的检索词";
	public static final String MSG_SEARCH_UPSTREAM = "地名检索暂时不可用，请稍后重试";
	public static final String MSG_LAYER_MISSING = "图层不存在";
	public static final String MSG_LAYER_EMPTY = "没有可识别的地理要素（需要 GeoJSON 或含经纬度的记录）";
	public static final String MSG_LAYER_INVALID = "图层数据不是合法 JSON";
	public static final String MSG_LAYER_TOO_MANY = "单图层最多 8000 个要素";
	public static final String MSG_LAYER_NAME = "请填写图层名称";
	public static final String MSG_ANALYZE_OP = "不支持的空间运算";
	public static final String MSG_ANALYZE_DISTANCE = "请填写缓冲距离（米）";
	public static final String MSG_ANALYZE_OTHER = "该运算需要第二组几何（other）";
	public static final String MSG_ANALYZE_EMPTY = "没有可运算的几何";
	public static final String MSG_RASTER_URL = "栅格图层需要 http(s) 服务地址；XYZ 须含 {z}/{x}/{y}，WMS 须含 layers";
	public static final String MSG_DEMO_MISSING = "没有这个示例";
	public static final String MSG_TILESET_URL = "三维切片图层需要 tileset.json 地址：内置示例填 hosted:<code>，外部数据填 http(s) 链接";
	public static final String MSG_TILESET_MISSING = "没有这个内置三维切片";
	public static final String MSG_SPATIAL_BBOX = "请给出合法的查询范围（minLon/minLat/maxLon/maxLat，且最小值小于最大值）";
	public static final String MSG_SPATIAL_POINT = "请给出合法的中心点经纬度";
	public static final String MSG_SPATIAL_RADIUS = "半径需在 0～200000 米之间";
	public static final String MSG_SPATIAL_GEOM = "请给出合法的 GeoJSON 几何（geometry 或 Feature）";
	public static final String MSG_SPATIAL_KIND = "只有矢量 / 热力图层支持空间查询";
	public static final String MSG_MVT_UNAVAILABLE = "当前数据库未启用 PostGIS，矢量瓦片不可用（请改用整层 GeoJSON 渲染）";
	public static final String MSG_MVT_ZXY = "瓦片坐标越界";

	public static String searchNoKey(String provider) {
		if (PROVIDER_AMAP.equals(provider)) {
			return "请先配置并启用高德密钥";
		}
		if (PROVIDER_TIANDITU.equals(provider)) {
			return "请先配置并启用天地图密钥";
		}
		if (PROVIDER_BAIDU.equals(provider)) {
			return "请先配置并启用百度密钥";
		}
		if (PROVIDER_GOOGLE.equals(provider)) {
			return "请先配置并启用 Google 密钥";
		}
		return MSG_SEARCH_NO_KEY;
	}

	private GisConstants() {
	}
}
