package com.mugsun.boot.gis.controller;

import cn.dev33.satoken.annotation.SaCheckLogin;
import cn.dev33.satoken.annotation.SaCheckPermission;
import cn.dev33.satoken.annotation.SaMode;
import com.mugsun.boot.gis.GisAnalyzeService;
import com.mugsun.boot.gis.GisConstants;
import com.mugsun.boot.gis.GisFeatureStore;
import com.mugsun.boot.gis.GisModuleService;
import com.mugsun.boot.gis.GisSpatialQueryService;
import com.mugsun.boot.gis.GisSpatialSupport;
import com.mugsun.boot.gis.GisVectorTileService;
import com.mugsun.boot.gis.entity.GisLayer;
import com.mugsun.boot.gis.mapper.GisLayerMapper;
import com.mugsun.core.tool.api.R;
import com.mugsun.core.tool.exception.ServiceException;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * 空间查询与矢量瓦片。图层一律先按 id 查出来（走 mapper，继承租户与逻辑删除规则），
 * 再拿 layer_id 查要素行，避免原生 SQL 绕过租户隔离。
 */
@RestController
@RequestMapping("/system/gis/spatial")
@SaCheckLogin
public class GisSpatialController {

	private final GisModuleService moduleService;
	private final GisLayerMapper layerMapper;
	private final GisSpatialQueryService queryService;
	private final GisVectorTileService tileService;
	private final GisSpatialSupport support;
	private final GisFeatureStore featureStore;

	public GisSpatialController(GisModuleService moduleService, GisLayerMapper layerMapper,
								GisSpatialQueryService queryService, GisVectorTileService tileService,
								GisSpatialSupport support, GisFeatureStore featureStore) {
		this.moduleService = moduleService;
		this.layerMapper = layerMapper;
		this.queryService = queryService;
		this.tileService = tileService;
		this.support = support;
		this.featureStore = featureStore;
	}

	/** 能力探测：前端据此决定走矢量瓦片还是整层 GeoJSON */
	@GetMapping("/status")
	@SaCheckPermission(value = { GisConstants.PERM_LAYER_LIST, GisConstants.PERM_WORKSPACE }, mode = SaMode.OR)
	public R<Map<String, Object>> status() {
		moduleService.requireEnabled();
		Map<String, Object> out = new LinkedHashMap<>();
		out.put("postgis", support.available());
		out.put("mvt", tileService.available());
		out.put("limitMax", GisConstants.SPATIAL_LIMIT_MAX);
		return R.data(out);
	}

	/**
	 * 要素行对账：要素表只是 data_json 的加速副本，同步失败只记日志，所以得能主动查漂移。
	 * 单独端点而不是并进 /status——这查询要扫图层表，不该每次开页都跑。
	 */
	@GetMapping("/drift")
	@SaCheckPermission(value = { GisConstants.PERM_LAYER_LIST, GisConstants.PERM_WORKSPACE }, mode = SaMode.OR)
	public R<Map<String, Object>> drift(@RequestParam(required = false, defaultValue = "0") int limit) {
		moduleService.requireEnabled();
		Map<String, Object> out = new LinkedHashMap<>();
		out.put("postgis", support.available());
		java.util.List<Map<String, Object>> rows = featureStore.drift(limit);
		out.put("count", rows.size());
		out.put("layers", rows);
		return R.data(out);
	}

	/** 视野范围内的要素：前端拖图/缩放后传当前 extent */
	@GetMapping("/bbox")
	@SaCheckPermission(value = { GisConstants.PERM_LAYER_LIST, GisConstants.PERM_WORKSPACE }, mode = SaMode.OR)
	public R<Map<String, Object>> bbox(@RequestParam Long layerId,
									   @RequestParam double minLon, @RequestParam double minLat,
									   @RequestParam double maxLon, @RequestParam double maxLat,
									   @RequestParam(required = false, defaultValue = "0") int limit,
									   @RequestParam(required = false) String engine) {
		moduleService.requireEnabled();
		return R.data(queryService.bbox(require(layerId), minLon, minLat, maxLon, maxLat, limit, forceJava(engine)));
	}

	/** 半径内的要素，距离以米计，结果 properties 带 meters */
	@GetMapping("/radius")
	@SaCheckPermission(value = { GisConstants.PERM_LAYER_LIST, GisConstants.PERM_WORKSPACE }, mode = SaMode.OR)
	public R<Map<String, Object>> radius(@RequestParam Long layerId,
										 @RequestParam double lon, @RequestParam double lat,
										 @RequestParam double meters,
										 @RequestParam(required = false, defaultValue = "0") int limit,
										 @RequestParam(required = false) String engine) {
		moduleService.requireEnabled();
		return R.data(queryService.radius(require(layerId), lon, lat, meters, limit, forceJava(engine)));
	}

	/**
	 * 与给定几何相交的要素（圈选、行政区筛选都走这个）。
	 *
	 * <p>layerId 用 {@link GisAnalyzeService#parseId} 解析而不是直接当 Number 取：雪花 ID 有 18 位，
	 * 超过 JS 的安全整数范围，浏览器端只能以字符串传，当数字收会静默丢精度变成「图层不存在」。
	 */
	@PostMapping("/intersects")
	@SaCheckPermission(value = { GisConstants.PERM_LAYER_LIST, GisConstants.PERM_WORKSPACE }, mode = SaMode.OR)
	public R<Map<String, Object>> intersects(@RequestBody Map<String, Object> body) {
		moduleService.requireEnabled();
		Long layerId = GisAnalyzeService.parseId(body.get("layerId"));
		int limit = body.get("limit") instanceof Number n ? n.intValue() : 0;
		return R.data(queryService.intersects(require(layerId), body.get("geometry"), limit,
			forceJava(body.get("engine") == null ? null : String.valueOf(body.get("engine")))));
	}

	/** 最近邻：按距离升序回最近的若干要素 */
	@GetMapping("/nearest")
	@SaCheckPermission(value = { GisConstants.PERM_LAYER_LIST, GisConstants.PERM_WORKSPACE }, mode = SaMode.OR)
	public R<Map<String, Object>> nearest(@RequestParam Long layerId,
										  @RequestParam double lon, @RequestParam double lat,
										  @RequestParam(required = false, defaultValue = "10") int limit,
										  @RequestParam(required = false) String engine) {
		moduleService.requireEnabled();
		return R.data(queryService.nearest(require(layerId), lon, lat, limit, forceJava(engine)));
	}

	/**
	 * 包含给定几何的要素。layerId 同样按字符串解析，避免雪花 ID 丢精度。
	 */
	@PostMapping("/contains")
	@SaCheckPermission(value = { GisConstants.PERM_LAYER_LIST, GisConstants.PERM_WORKSPACE }, mode = SaMode.OR)
	public R<Map<String, Object>> contains(@RequestBody Map<String, Object> body) {
		moduleService.requireEnabled();
		Long layerId = GisAnalyzeService.parseId(body.get("layerId"));
		int limit = body.get("limit") instanceof Number n ? n.intValue() : 0;
		return R.data(queryService.contains(require(layerId), body.get("geometry"), limit,
			forceJava(body.get("engine") == null ? null : String.valueOf(body.get("engine")))));
	}

	/**
	 * 对图层要素做米制缓冲，回缓冲后的几何。默认带 limit，防止万级图层一次吐整层。
	 */
	@PostMapping("/buffer")
	@SaCheckPermission(value = { GisConstants.PERM_LAYER_LIST, GisConstants.PERM_WORKSPACE }, mode = SaMode.OR)
	public R<Map<String, Object>> buffer(@RequestBody Map<String, Object> body) {
		moduleService.requireEnabled();
		Long layerId = GisAnalyzeService.parseId(body.get("layerId"));
		double meters = body.get("distance") instanceof Number n
			? n.doubleValue() : GisConstants.BUFFER_DEFAULT_M;
		int limit = body.get("limit") instanceof Number n ? n.intValue() : 0;
		return R.data(queryService.buffer(require(layerId), meters, limit,
			forceJava(body.get("engine") == null ? null : String.valueOf(body.get("engine")))));
	}

	/** 矢量瓦片：application/vnd.mapbox-vector-tile，空瓦片回 0 字节 */
	@GetMapping("/mvt/{layerId}/{z}/{x}/{y}")
	@SaCheckPermission(value = { GisConstants.PERM_LAYER_LIST, GisConstants.PERM_WORKSPACE }, mode = SaMode.OR)
	public ResponseEntity<byte[]> mvt(@PathVariable Long layerId,
									  @PathVariable int z, @PathVariable int x, @PathVariable int y) {
		moduleService.requireEnabled();
		byte[] pbf = tileService.tile(require(layerId), z, x, y);
		return ResponseEntity.ok()
			.contentType(MediaType.parseMediaType(GisConstants.MVT_CONTENT_TYPE))
			.cacheControl(CacheControl.maxAge(5, TimeUnit.MINUTES))
			.body(pbf);
	}

	/** engine=java 强制走 Java 回落路径，仅用于诊断与压测对比，不影响默认行为 */
	private static boolean forceJava(String engine) {
		return GisConstants.ENGINE_JAVA.equalsIgnoreCase(engine);
	}

	private GisLayer require(Long id) {
		GisLayer row = id == null ? null : layerMapper.selectOneById(id);
		if (row == null) {
			throw new ServiceException(GisConstants.MSG_LAYER_MISSING);
		}
		return row;
	}
}
