package com.mugsun.boot.gis.controller;

import cn.dev33.satoken.annotation.SaCheckLogin;
import cn.dev33.satoken.annotation.SaCheckPermission;
import cn.dev33.satoken.annotation.SaMode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mugsun.boot.gis.GisAnalyzeService;
import com.mugsun.boot.gis.Gis3dTilesSpec;
import com.mugsun.boot.gis.GisConstants;
import com.mugsun.boot.gis.GisFeatureStore;
import com.mugsun.boot.gis.GisFormatService;
import com.mugsun.boot.gis.GisModuleService;
import com.mugsun.boot.gis.GisRasterSpec;
import com.mugsun.boot.gis.entity.GisLayer;
import com.mugsun.boot.gis.mapper.GisLayerMapper;
import com.mugsun.boot.tenant.TenantContext;
import com.mugsun.core.tool.api.R;
import com.mugsun.core.tool.exception.ServiceException;
import com.mybatisflex.core.paginate.Page;
import com.mybatisflex.core.query.QueryWrapper;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 通用图层库：入站任意可识别坐标 → WGS84 FeatureCollection 落库，工作台按 layerId 叠加。
 */
@RestController
@RequestMapping("/system/gis/layer")
@SaCheckLogin
public class GisLayerController {

	private final GisModuleService moduleService;
	private final GisFormatService formatService;
	private final GisLayerMapper layerMapper;
	private final ObjectMapper objectMapper;
	private final GisFeatureStore featureStore;

	public GisLayerController(GisModuleService moduleService, GisFormatService formatService,
							  GisLayerMapper layerMapper, ObjectMapper objectMapper,
							  GisFeatureStore featureStore) {
		this.moduleService = moduleService;
		this.formatService = formatService;
		this.layerMapper = layerMapper;
		this.objectMapper = objectMapper;
		this.featureStore = featureStore;
	}

	@GetMapping("/page")
	@SaCheckPermission(GisConstants.PERM_LAYER_LIST)
	public R<Page<GisLayer>> page(@RequestParam(defaultValue = "1") long pageNum,
								  @RequestParam(defaultValue = "20") long pageSize,
								  @RequestParam(required = false) String name) {
		moduleService.requireEnabled();
		QueryWrapper q = QueryWrapper.create().orderBy("id", false);
		if (name != null && !name.isBlank()) {
			q.like("name", name.trim());
		}
		Page<GisLayer> page = layerMapper.paginate(pageNum, pageSize, q);
		page.getRecords().forEach(this::stripPayload);
		return R.data(page);
	}

	@GetMapping("/list")
	@SaCheckPermission(value = { GisConstants.PERM_LAYER_LIST, GisConstants.PERM_WORKSPACE }, mode = SaMode.OR)
	public R<List<GisLayer>> list() {
		moduleService.requireEnabled();
		List<GisLayer> rows = layerMapper.selectListByQuery(
			QueryWrapper.create().eq("status", GisConstants.STATUS_ENABLE).orderBy("id", false));
		if (rows.size() > 200) {
			rows = rows.subList(0, 200);
		}
		rows.forEach(this::stripPayload);
		return R.data(rows);
	}

	@GetMapping("/detail/{id}")
	@SaCheckPermission(value = { GisConstants.PERM_LAYER_LIST, GisConstants.PERM_WORKSPACE }, mode = SaMode.OR)
	public R<GisLayer> detail(@PathVariable Long id) {
		moduleService.requireEnabled();
		return R.data(require(id));
	}

	@PostMapping("/ingest")
	@SaCheckPermission(value = { GisConstants.PERM_LAYER_SAVE, GisConstants.PERM_WORKSPACE }, mode = SaMode.OR)
	public R<Map<String, Object>> ingest(@RequestBody Object body) {
		moduleService.requireEnabled();
		return R.data(formatService.normalizeUnknown(unwrap(body)));
	}

	@PostMapping("/submit")
	@SaCheckPermission(GisConstants.PERM_LAYER_SAVE)
	public R<GisLayer> submit(@RequestBody Map<String, Object> body) {
		moduleService.requireEnabled();
		String name = stringOf(body.get("name"));
		if (name == null || name.isBlank()) {
			throw new ServiceException(GisConstants.MSG_LAYER_NAME);
		}
		Object payload = body.containsKey("payload") ? body.get("payload") : body.get("dataJson");
		if (payload == null) {
			throw new ServiceException(GisConstants.MSG_LAYER_EMPTY);
		}
		String kind = stringOf(body.get("kind"));
		if (kind == null || kind.isBlank()) {
			kind = GisConstants.KIND_VECTOR;
		}
		kind = kind.trim();
		String json;
		Integer featureCount = 0;
		String bbox = null;
		String crs = GisConstants.CRS_WGS84;
		if (GisRasterSpec.isRaster(kind) || Gis3dTilesSpec.is3dTiles(kind)) {
			Map<String, Object> spec = Gis3dTilesSpec.is3dTiles(kind)
				? Gis3dTilesSpec.normalize(payload)
				: GisRasterSpec.normalize(kind, payload);
			try {
				json = objectMapper.writeValueAsString(spec);
			} catch (Exception e) {
				throw new ServiceException(GisConstants.MSG_LAYER_INVALID);
			}
		} else {
			if (!GisConstants.KIND_HEATMAP.equals(kind)) {
				kind = GisConstants.KIND_VECTOR;
			}
			Map<String, Object> normalized = formatService.normalizeUnknown(payload);
			try {
				json = objectMapper.writeValueAsString(normalized);
			} catch (Exception e) {
				throw new ServiceException(GisConstants.MSG_LAYER_INVALID);
			}
			Object countVal = normalized.get("count");
			featureCount = countVal instanceof Number n ? n.intValue() : 0;
			bbox = encodeBbox(normalized.get("bbox"));
		}
		GisLayer row;
		Long id = GisAnalyzeService.parseId(body.get("id"));
		if (id != null) {
			row = require(id);
		} else {
			row = new GisLayer();
			row.sanitizeForInsert();
			row.setTenantId(TenantContext.current());
		}
		row.setName(name.trim());
		row.setKind(kind);
		row.setCrs(crs);
		row.setDataJson(json);
		row.setStyleJson(stringOf(body.get("styleJson")));
		row.setFeatureCount(featureCount);
		row.setBbox(bbox);
		row.setRemark(stringOf(body.get("remark")));
		Integer status = body.get("status") instanceof Number s ? s.intValue() : GisConstants.STATUS_ENABLE;
		row.setStatus(status == 0 ? GisConstants.STATUS_DISABLE : GisConstants.STATUS_ENABLE);
		if (row.getId() == null) {
			layerMapper.insertSelective(row);
		} else {
			row.sanitizeForUpdate();
			layerMapper.update(row);
		}
		// 要素行是空间查询的加速副本，同步失败只降级不报错（见 GisFeatureStore）
		featureStore.sync(row.getId(), row.getTenantId(), row.getKind(), row.getDataJson());
		return R.data(row);
	}

	@PostMapping("/remove")
	@SaCheckPermission(GisConstants.PERM_LAYER_REMOVE)
	public R<Void> remove(@RequestBody List<Long> ids) {
		moduleService.requireEnabled();
		if (ids != null) {
			ids.forEach(id -> {
				layerMapper.deleteById(id);
				featureStore.dropLayer(id);
			});
		}
		return R.success("删除成功");
	}

	private GisLayer require(Long id) {
		GisLayer row = layerMapper.selectOneById(id);
		if (row == null) {
			throw new ServiceException(GisConstants.MSG_LAYER_MISSING);
		}
		return row;
	}

	private void stripPayload(GisLayer row) {
		row.setDataJson(null);
	}

	private static Object unwrap(Object body) {
		if (body instanceof Map<?, ?> map) {
			if (map.containsKey("payload")) {
				return map.get("payload");
			}
			if (map.containsKey("dataJson")) {
				return map.get("dataJson");
			}
		}
		return body;
	}

	private static String stringOf(Object v) {
		return v == null ? null : String.valueOf(v);
	}

	private String encodeBbox(Object bbox) {
		if (bbox == null) {
			return null;
		}
		try {
			return objectMapper.writeValueAsString(bbox);
		} catch (Exception e) {
			return null;
		}
	}
}
