package com.mugsun.boot.gis.controller;

import cn.dev33.satoken.annotation.SaCheckLogin;
import cn.dev33.satoken.annotation.SaCheckPermission;
import com.mugsun.boot.common.repeat.RepeatSubmit;
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
	@RepeatSubmit
	@PostMapping("/provider/submit")
	public R<Void> submit(@RequestBody GisMapProvider body) {
		moduleService.requireEnabled();
		if (body == null || body.getProvider() == null || !GisConstants.PROVIDERS.contains(body.getProvider())) {
			throw new ServiceException("底图请从天地图、高德、百度或谷歌里选一个");
		}
		String key = blankToNull(body.getApiKey());
		String secret = blankToNull(body.getSecret());
		if (key != null && key.length() > 128) {
			throw new ServiceException("密钥请控制在 128 个字以内。请到申请页面复制完整密钥后再贴");
		}
		if (secret != null && secret.length() > 128) {
			throw new ServiceException("安全密钥请控制在 128 个字以内");
		}
		String remark = body.getRemark() == null ? "" : body.getRemark().trim();
		if (remark.length() > 255) {
			throw new ServiceException("备注请控制在 255 个字以内");
		}
		String extra = blankToNull(body.getExtraJson());
		if (extra != null && extra.length() > 20000) {
			throw new ServiceException("附加配置过长。请删掉用不到的项后再保存");
		}
		if (body.getEnabled() != null && body.getEnabled() != 0 && body.getEnabled() != 1) {
			throw new ServiceException("启用状态请选择开或关");
		}
		GisMapProvider exist = providerMapper.selectOneByQuery(
			QueryWrapper.create().eq("provider", body.getProvider()));
		boolean creating = exist == null;
		if (creating && key == null) {
			throw new ServiceException("请先填写密钥。填完后地图才能用这个底图");
		}
		String label = providerLabel(body.getProvider());
		if (creating) {
			body.setId(null);
			body.setApiKey(key);
			body.setSecret(secret);
			body.setRemark(remark.isEmpty() ? null : remark);
			body.setExtraJson(extra);
			body.sanitizeForInsert();
			body.setTenantId(TenantContext.current());
			if (body.getEnabled() == null) {
				body.setEnabled(GisConstants.STATUS_ENABLE);
			}
			providerMapper.insertSelective(body);
			return R.success("已保存「" + label + "」。地图会用这份密钥");
		}
		exist.setEnabled(body.getEnabled() == null ? exist.getEnabled() : body.getEnabled());
		exist.setExtraJson(extra == null ? exist.getExtraJson() : extra);
		exist.setRemark(remark.isEmpty() ? exist.getRemark() : remark);
		if (key != null) {
			exist.setApiKey(key);
		}
		if (secret != null) {
			exist.setSecret(secret);
		}
		exist.sanitizeForUpdate();
		providerMapper.update(exist);
		return R.success("已保存「" + label + "」。地图会用这份配置");
	}

	@SaCheckPermission(GisConstants.PERM_PROVIDER_REMOVE)
	@RepeatSubmit
	@PostMapping("/provider/remove")
	public R<Void> remove(@RequestBody List<Long> ids) {
		moduleService.requireEnabled();
		if (ids == null || ids.isEmpty()) {
			throw new ServiceException("请先选择要清除的底图");
		}
		if (ids.stream().anyMatch(id -> id == null)) {
			throw new ServiceException("有底图不存在。请刷新页面后再清除");
		}
		List<Long> distinct = ids.stream().distinct().toList();
		List<GisMapProvider> rows = providerMapper.selectListByQuery(QueryWrapper.create().in("id", distinct));
		if (rows.size() != distinct.size()) {
			throw new ServiceException("有底图不存在，或已经被清除。请刷新页面后再试");
		}
		providerMapper.deleteBatchByIds(distinct);
		if (rows.size() == 1) {
			return R.success("已清除「" + providerLabel(rows.get(0).getProvider()) + "」。地图会改用其他已配置的底图");
		}
		return R.success("已清除 " + rows.size() + " 个底图。地图会改用其他已配置的底图");
	}

	private static String blankToNull(String value) {
		if (value == null || value.isBlank()) {
			return null;
		}
		return value.trim();
	}

	private static String providerLabel(String code) {
		return switch (code) {
			case "tianditu" -> "天地图";
			case "amap" -> "高德";
			case "baidu" -> "百度";
			default -> "谷歌";
		};
	}

	private void maskSecrets(GisMapProvider row) {
		row.setApiKey(null);
		row.setSecret(null);
	}
}
