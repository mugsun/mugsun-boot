package com.mugsun.boot.gis.controller;

import cn.dev33.satoken.annotation.SaCheckLogin;
import cn.dev33.satoken.annotation.SaCheckPermission;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mugsun.boot.common.repeat.RepeatSubmit;
import com.mugsun.boot.gis.GisConstants;
import com.mugsun.boot.gis.GisModuleService;
import com.mugsun.boot.gis.entity.GisScene;
import com.mugsun.boot.gis.mapper.GisSceneMapper;
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

/**
 * GIS 场景 CRUD。
 */
@RestController
@RequestMapping("/system/gis/scene")
@SaCheckLogin
public class GisSceneController {

	private static final String DEFAULT_SCENE_JSON = "{\"viewMode\":\"2d\","
		+ "\"baseMap\":{\"provider\":\"tianditu\",\"style\":\"img_label\"},"
		+ "\"view2d\":{\"center\":[116.397428,39.90923],\"zoom\":11,\"rotation\":0},"
		+ "\"view3d\":{\"lon\":116.397428,\"lat\":39.90923,\"height\":18000,\"heading\":0,\"pitch\":-45},"
		+ "\"layers\":[]}";

	private final GisModuleService moduleService;
	private final GisSceneMapper sceneMapper;
	private final ObjectMapper objectMapper;

	public GisSceneController(GisModuleService moduleService, GisSceneMapper sceneMapper, ObjectMapper objectMapper) {
		this.moduleService = moduleService;
		this.sceneMapper = sceneMapper;
		this.objectMapper = objectMapper;
	}

		@GetMapping("/page")
	@SaCheckPermission(value = { GisConstants.PERM_WORKSPACE, GisConstants.PERM_SCENE_LIST },
		mode = cn.dev33.satoken.annotation.SaMode.OR)
	public R<Page<GisScene>> page(@RequestParam(defaultValue = "1") long pageNum,
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
		return R.data(sceneMapper.paginate(pageNum, pageSize, q));
	}

	@GetMapping("/detail/{id}")
	@SaCheckPermission(GisConstants.PERM_WORKSPACE)
	public R<GisScene> detail(@PathVariable Long id) {
		moduleService.requireEnabled();
		GisScene scene = sceneMapper.selectOneById(id);
		if (scene == null) {
			throw new ServiceException(GisConstants.MSG_SCENE_MISSING);
		}
		return R.data(scene);
	}

	@SaCheckPermission(GisConstants.PERM_SCENE_SAVE)
	@RepeatSubmit
	@PostMapping("/submit")
	public R<GisScene> submit(@RequestBody GisScene body) {
		moduleService.requireEnabled();
		if (body == null || body.getName() == null || body.getName().isBlank()) {
			throw new ServiceException("请填写场景名称。用来在列表和地图上区分");
		}
		String name = body.getName().trim();
		if (name.length() > 128) {
			throw new ServiceException("场景名称请控制在 128 个字以内");
		}
		String remark = body.getRemark() == null ? "" : body.getRemark().trim();
		if (remark.length() > 255) {
			throw new ServiceException("备注请控制在 255 个字以内");
		}
		if (body.getStatus() != null && body.getStatus() != 0 && body.getStatus() != 1) {
			throw new ServiceException("状态请选择启用或停用");
		}
		String json = body.getSceneJson() == null || body.getSceneJson().isBlank()
			? DEFAULT_SCENE_JSON : body.getSceneJson().trim();
		if (json.length() > 200000) {
			throw new ServiceException("场景内容过长。请减少标绘后再保存");
		}
		try {
			objectMapper.readTree(json);
		} catch (Exception e) {
			throw new ServiceException(GisConstants.MSG_JSON_INVALID);
		}
		QueryWrapper duplicated = QueryWrapper.create().eq("name", name);
		if (body.getId() != null) {
			duplicated.ne("id", body.getId());
		}
		if (sceneMapper.selectCountByQuery(duplicated) > 0) {
			throw new ServiceException("已经有叫「" + name + "」的场景。请换一个名称，或打开原来那个");
		}
		boolean creating = body.getId() == null;
		body.setName(name);
		body.setRemark(remark.isEmpty() ? null : remark);
		body.setSceneJson(json);
		if (body.getStatus() == null) {
			body.setStatus(GisConstants.STATUS_ENABLE);
		}
		if (creating) {
			body.sanitizeForInsert();
			body.setTenantId(TenantContext.current());
			sceneMapper.insertSelective(body);
		} else {
			GisScene exist = sceneMapper.selectOneById(body.getId());
			if (exist == null) {
				throw new ServiceException("这个场景不存在。请刷新列表后再保存");
			}
			exist.setName(name);
			exist.setSceneJson(json);
			exist.setStatus(body.getStatus());
			exist.setRemark(body.getRemark());
			exist.sanitizeForUpdate();
			sceneMapper.update(exist);
			body = exist;
		}
		R<GisScene> result = R.data(body);
		result.setMsg(creating ? "已创建。可以在场景列表里打开它" : "已保存。地图会用这份场景");
		return result;
	}

	@SaCheckPermission(GisConstants.PERM_SCENE_REMOVE)
	@RepeatSubmit
	@PostMapping("/remove")
	public R<Void> remove(@RequestBody List<Long> ids) {
		moduleService.requireEnabled();
		if (ids == null || ids.isEmpty()) {
			throw new ServiceException("请先选择要删除的场景");
		}
		if (ids.stream().anyMatch(id -> id == null)) {
			throw new ServiceException("有场景不存在。请刷新列表后再删除");
		}
		List<Long> distinct = ids.stream().distinct().toList();
		long found = sceneMapper.selectCountByQuery(QueryWrapper.create().in("id", distinct));
		if (found != distinct.size()) {
			throw new ServiceException("有场景不存在，或已经被删除。请刷新列表后再试");
		}
		sceneMapper.deleteBatchByIds(distinct);
		return R.success("已删除 " + distinct.size() + " 个场景。列表和地图里不会再出现");
	}
}
