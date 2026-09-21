package com.mugsun.boot.gis.controller;

import cn.dev33.satoken.annotation.SaCheckLogin;
import cn.dev33.satoken.annotation.SaCheckPermission;
import com.fasterxml.jackson.databind.ObjectMapper;
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
	@PostMapping("/submit")
	public R<GisScene> submit(@RequestBody GisScene body) {
		moduleService.requireEnabled();
		if (body.getName() == null || body.getName().isBlank()) {
			throw new ServiceException("请填写场景名称");
		}
		String json = body.getSceneJson() == null || body.getSceneJson().isBlank()
			? DEFAULT_SCENE_JSON : body.getSceneJson().trim();
		try {
			objectMapper.readTree(json);
		} catch (Exception e) {
			throw new ServiceException(GisConstants.MSG_JSON_INVALID);
		}
		body.setSceneJson(json);
		if (body.getStatus() == null) {
			body.setStatus(GisConstants.STATUS_ENABLE);
		}
		if (body.getId() == null) {
			body.sanitizeForInsert();
			body.setTenantId(TenantContext.current());
			sceneMapper.insertSelective(body);
		} else {
			GisScene exist = sceneMapper.selectOneById(body.getId());
			if (exist == null) {
				throw new ServiceException(GisConstants.MSG_SCENE_MISSING);
			}
			exist.setName(body.getName());
			exist.setSceneJson(json);
			exist.setStatus(body.getStatus());
			exist.setRemark(body.getRemark());
			exist.sanitizeForUpdate();
			sceneMapper.update(exist);
			body = exist;
		}
		return R.data(body);
	}

	@SaCheckPermission(GisConstants.PERM_SCENE_REMOVE)
	@PostMapping("/remove")
	public R<Void> remove(@RequestBody List<Long> ids) {
		moduleService.requireEnabled();
		if (ids != null) {
			ids.forEach(sceneMapper::deleteById);
		}
		return R.success("删除成功");
	}
}
