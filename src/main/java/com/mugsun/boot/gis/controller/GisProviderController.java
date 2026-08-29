package com.mugsun.boot.gis.controller;

import cn.dev33.satoken.annotation.SaCheckLogin;
import cn.dev33.satoken.annotation.SaCheckPermission;
import com.mugsun.boot.gis.GisConstants;
import com.mugsun.boot.gis.GisModuleService;
import com.mugsun.boot.gis.entity.GisMapProvider;
import com.mugsun.boot.gis.mapper.GisMapProviderMapper;
import com.mugsun.core.tool.api.R;
import com.mugsun.core.tool.exception.ServiceException;
import com.mugsun.boot.tenant.TenantContext;
import com.mybatisflex.core.query.QueryWrapper;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * GIS 状态与底图供应商配置。
 */
@RestController
@RequestMapping("/system/gis")
@SaCheckLogin
public class GisProviderController {

	private final GisModuleService moduleService;
	private final GisMapProviderMapper providerMapper;

	public GisProviderController(GisModuleService moduleService, GisMapProviderMapper providerMapper) {
		this.moduleService = moduleService;
		this.providerMapper = providerMapper;
	}

	@GetMapping("/status")
	public R<Map<String, Object>> status() {
		boolean enabled = moduleService.isEnabled();
		Map<String, Object> data = new LinkedHashMap<>();
		data.put("enabled", enabled);
		List<Map<String, Object>> providers = new ArrayList<>();
		if (enabled) {
			List<GisMapProvider> rows = providerMapper.selectListByQuery(QueryWrapper.create().orderBy("provider", true));
			Map<String, GisMapProvider> byCode = new LinkedHashMap<>();
			for (GisMapProvider row : rows) {
				byCode.put(row.getProvider(), row);
			}
			for (String code : GisConstants.PROVIDERS) {
				GisMapProvider row = byCode.get(code);
				Map<String, Object> item = new LinkedHashMap<>();
				item.put("provider", code);
				boolean configured = row != null && row.getApiKey() != null && !row.getApiKey().isBlank();
				item.put("configured", configured);
				item.put("enabled", row != null && Integer.valueOf(GisConstants.STATUS_ENABLE).equals(row.getEnabled()));
				item.put("id", row == null ? null : row.getId());
				providers.add(item);
			}
		}
		data.put("providers", providers);
		if (enabled) {
			data.put("terrainUrl", moduleService.terrainUrl());
			data.put("tilesets", GisConstants.TILESETS);
		}
		return R.data(data);
	}

	@GetMapping("/provider/list")
	public R<List<GisMapProvider>> list() {
		moduleService.requireEnabled();
		List<GisMapProvider> rows = providerMapper.selectListByQuery(QueryWrapper.create().orderBy("provider", true));
		rows.forEach(this::maskSecrets);
		return R.data(rows);
	}

	@SaCheckPermission(GisConstants.PERM_PROVIDER_SAVE)
	@PostMapping("/provider/submit")
	public R<Void> submit(@RequestBody GisMapProvider body) {
		moduleService.requireEnabled();
		if (body.getProvider() == null || !GisConstants.PROVIDERS.contains(body.getProvider())) {
			throw new ServiceException(GisConstants.MSG_PROVIDER_UNKNOWN);
		}
		if (body.getApiKey() != null && body.getApiKey().isBlank()) {
			body.setApiKey(null);
		}
		if (body.getSecret() != null && body.getSecret().isBlank()) {
			body.setSecret(null);
		}
		GisMapProvider exist = providerMapper.selectOneByQuery(
			QueryWrapper.create().eq("provider", body.getProvider()));
		if (exist == null) {
			body.setId(null);
			body.sanitizeForInsert();
			body.setTenantId(TenantContext.current());
			if (body.getEnabled() == null) {
				body.setEnabled(GisConstants.STATUS_ENABLE);
			}
			providerMapper.insertSelective(body);
		} else {
			exist.setEnabled(body.getEnabled() == null ? exist.getEnabled() : body.getEnabled());
			exist.setExtraJson(body.getExtraJson());
			exist.setRemark(body.getRemark());
			if (body.getApiKey() != null) {
				exist.setApiKey(body.getApiKey());
			}
			if (body.getSecret() != null) {
				exist.setSecret(body.getSecret());
			}
			exist.sanitizeForUpdate();
			providerMapper.update(exist);
		}
		return R.success("操作成功");
	}

	@SaCheckPermission(GisConstants.PERM_PROVIDER_REMOVE)
	@PostMapping("/provider/remove")
	public R<Void> remove(@RequestBody List<Long> ids) {
		moduleService.requireEnabled();
		if (ids != null) {
			ids.forEach(providerMapper::deleteById);
		}
		return R.success("删除成功");
	}

	private void maskSecrets(GisMapProvider row) {
		row.setApiKey(null);
		row.setSecret(null);
	}
}
