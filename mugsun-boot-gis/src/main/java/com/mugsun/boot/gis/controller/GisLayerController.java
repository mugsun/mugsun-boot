package com.mugsun.boot.gis.controller;

import cn.dev33.satoken.annotation.SaCheckLogin;
import cn.dev33.satoken.annotation.SaCheckPermission;
import cn.dev33.satoken.annotation.SaMode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mugsun.boot.common.repeat.RepeatSubmit;
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
import org.springframework.http.MediaType;
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
		if (pageNum < 1) {
			throw new ServiceException("页码请从 1 开始");
		}
		if (pageSize < 1 || pageSize > 100) {
			throw new ServiceException("每页条数请选择 1 到 100");
		}
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

	@PostMapping(value = "/ingest", consumes = MediaType.APPLICATION_JSON_VALUE)
	@SaCheckPermission(value = { GisConstants.PERM_LAYER_SAVE, GisConstants.PERM_WORKSPACE }, mode = SaMode.OR)
	public R<Map<String, Object>> ingest(@RequestBody Object body) {
		moduleService.requireEnabled();
		return R.data(formatService.normalizeUnknown(unwrap(body)));
	}

	/**
	 * 原文入站通道：KML / GPX 等带标签的格式必须走 text/plain。
	 * JSON body 里的字符串会被全局 XSS 反序列化器按富文本过滤，标签一被剥掉就再也解析不出要素；
	 * text/plain 不经 Jackson，原文完整到达，解析出的属性值再由 {@link com.mugsun.boot.gis.GisTextIngest} 严格净化。
	 */
	@PostMapping(value = "/ingest", consumes = MediaType.TEXT_PLAIN_VALUE)
	@SaCheckPermission(value = { GisConstants.PERM_LAYER_SAVE, GisConstants.PERM_WORKSPACE }, mode = SaMode.OR)
	public R<Map<String, Object>> ingestText(@RequestBody String raw) {
		moduleService.requireEnabled();
		return R.data(formatService.normalizeUnknown(raw));
	}

	@PostMapping("/submit")
	@SaCheckPermission(GisConstants.PERM_LAYER_SAVE)
	@RepeatSubmit
	public R<GisLayer> submit(@RequestBody Map<String, Object> body) {
		moduleService.requireEnabled();
		if (body == null) {
			throw new ServiceException("请先填写图层名称和数据。用来在列表和地图上区分");
		}
		String name = stringOf(body.get("name"));
		if (name == null || name.isBlank()) {
			throw new ServiceException("请填写图层名称。用来在列表和地图上区分");
		}
		name = name.trim();
		if (name.length() > 128) {
			throw new ServiceException("图层名称请控制在 128 个字以内");
		}
		String remark = stringOf(body.get("remark"));
		remark = remark == null ? "" : remark.trim();
		if (remark.length() > 255) {
			throw new ServiceException("备注请控制在 255 个字以内");
		}
		if (body.get("status") instanceof Number statusNum) {
			int status = statusNum.intValue();
			if (status != 0 && status != 1) {
				throw new ServiceException("状态请选择启用或停用");
			}
		}
		Object payload = body.containsKey("payload") ? body.get("payload") : body.get("dataJson");
		if (payload == null) {
			throw new ServiceException("请先贴上地理数据，或填写服务地址。没有数据就没法在地图上显示");
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
		List<String> warnings = List.of();
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
			// 拓扑警告说明的是「这次保存改了什么」，属于本次操作的回执而非几何数据，
			// 从落库的 FeatureCollection 里摘出来，改由响应 msg 告诉用户
			Object warned = normalized.remove("warnings");
			if (warned instanceof List<?> list && !list.isEmpty()) {
				warnings = list.stream().map(String::valueOf).toList();
			}
			try {
				json = objectMapper.writeValueAsString(normalized);
			} catch (Exception e) {
				throw new ServiceException(GisConstants.MSG_LAYER_INVALID);
			}
			Object countVal = normalized.get("count");
			featureCount = countVal instanceof Number n ? n.intValue() : 0;
			bbox = encodeBbox(normalized.get("bbox"));
		}
		if (json.length() > 200000) {
			throw new ServiceException("图层内容过长。请减少要素后再保存");
		}
		Long id = GisAnalyzeService.parseId(body.get("id"));
		QueryWrapper duplicated = QueryWrapper.create().eq("name", name);
		if (id != null) {
			duplicated.ne("id", id);
		}
		if (layerMapper.selectCountByQuery(duplicated) > 0) {
			throw new ServiceException("已经有叫「" + name + "」的图层。请换一个名称，或打开原来那个");
		}
		boolean creating = id == null;
		GisLayer row;
		if (id != null) {
			row = require(id);
		} else {
			row = new GisLayer();
			row.sanitizeForInsert();
			row.setTenantId(TenantContext.current());
		}
		row.setName(name);
		row.setKind(kind);
		row.setCrs(crs);
		row.setDataJson(json);
		row.setStyleJson(stringOf(body.get("styleJson")));
		row.setFeatureCount(featureCount);
		row.setBbox(bbox);
		row.setRemark(remark.isEmpty() ? null : remark);
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
		R<GisLayer> res = R.data(row);
		if (!warnings.isEmpty()) {
			row.setWarnings(warnings);
			res.setMsg("已保存。有 " + warnings.size() + " 处几何被自动修好："
				+ String.join("；", warnings.subList(0, Math.min(5, warnings.size())))
				+ "。请在地图上核对这几处");
		} else {
			res.setMsg(creating ? "已新增。列表里可以打开，也能叠加到地图" : "已保存。地图会用这份图层");
		}
		return res;
	}

	@PostMapping("/remove")
	@SaCheckPermission(GisConstants.PERM_LAYER_REMOVE)
	@RepeatSubmit
	public R<Void> remove(@RequestBody List<Long> ids) {
		moduleService.requireEnabled();
		if (ids == null || ids.isEmpty()) {
			throw new ServiceException("请先选择要删除的图层");
		}
		if (ids.stream().anyMatch(id -> id == null)) {
			throw new ServiceException("有图层不存在。请刷新列表后再删除");
		}
		List<Long> distinct = ids.stream().distinct().toList();
		long found = layerMapper.selectCountByQuery(QueryWrapper.create().in("id", distinct));
		if (found != distinct.size()) {
			throw new ServiceException("有图层不存在，或已经被删除。请刷新列表后再试");
		}
		layerMapper.deleteBatchByIds(distinct);
		distinct.forEach(featureStore::dropLayer);
		return R.success("已删除 " + distinct.size() + " 个图层。列表和地图里不会再出现");
	}

	private GisLayer require(Long id) {
		GisLayer row = layerMapper.selectOneById(id);
		if (row == null) {
			throw new ServiceException("这个图层不存在。请刷新列表后再试");
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
