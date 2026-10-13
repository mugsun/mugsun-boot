package com.mugsun.boot.gis.controller;

import cn.dev33.satoken.annotation.SaCheckLogin;
import cn.dev33.satoken.annotation.SaCheckPermission;
import com.mugsun.boot.common.repeat.RepeatSubmit;
import com.mugsun.boot.gis.GisConstants;
import com.mugsun.boot.gis.GisModuleService;
import com.mugsun.boot.gis.entity.GisMapProvider;
import com.mugsun.boot.gis.mapper.GisMapProviderMapper;
import com.mugsun.boot.gis.engine.GisUpstreamHttp;
import com.mugsun.boot.gis.xyz.GisXyzPresets;
import com.mugsun.boot.gis.xyz.GisXyzTemplate;
import com.mugsun.boot.gis.xyz.GisXyzTileService;
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
	private final GisXyzTileService xyz;

	public GisProviderController(GisModuleService moduleService, GisMapProviderMapper providerMapper,
								 GisXyzTileService xyz) {
		this.moduleService = moduleService;
		this.providerMapper = providerMapper;
		this.xyz = xyz;
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
				boolean configured;
				boolean on;
				if (GisConstants.PROVIDER_XYZ.equals(code)) {
					GisXyzTemplate t = xyzEffective(row);
					configured = t != null;
					on = configured && (row == null || Integer.valueOf(GisConstants.STATUS_ENABLE).equals(row.getEnabled()));
					item.put("attribution", t == null ? "" : t.attribution());
					item.put("maxZoom", t == null ? null : t.maxZoom());
				} else {
					configured = row != null && row.getApiKey() != null && !row.getApiKey().isBlank();
					on = row != null && Integer.valueOf(GisConstants.STATUS_ENABLE).equals(row.getEnabled());
					item.put("attribution", vendorAttribution(code));
				}
				item.put("configured", configured);
				item.put("enabled", on);
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
			throw new ServiceException("底图请从天地图、高德、百度、谷歌或自定义瓦片里选一个");
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
		boolean isXyz = GisConstants.PROVIDER_XYZ.equals(body.getProvider());
		if (isXyz) {
			if (!xyz.available()) {
				throw new ServiceException(GisConstants.MSG_XYZ_DISABLED);
			}
			// 校验并规范化模板：https、{z}{x}{y}、白名单域名、非 IP；归属文本去尖括号
			extra = extra == null ? null : xyz.normalize(extra);
		}
		if (body.getEnabled() != null && body.getEnabled() != 0 && body.getEnabled() != 1) {
			throw new ServiceException("启用状态请选择开或关");
		}
		GisMapProvider exist = providerMapper.selectOneByQuery(
			QueryWrapper.create().eq("provider", body.getProvider()));
		boolean creating = exist == null;
		if (creating && isXyz && extra == null) {
			throw new ServiceException("请先选择预设或填写瓦片地址");
		}
		if (creating && key == null && GisConstants.requiresKey(body.getProvider())) {
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

	/** 自定义瓦片的当前配置、预设与部署白名单（白名单只读，由运维在 yml 里改） */
	@SaCheckPermission(GisConstants.PERM_PROVIDER_LIST)
	@GetMapping("/provider/xyz")
	public R<Map<String, Object>> xyzConfig() {
		moduleService.requireEnabled();
		GisMapProvider row = providerMapper.selectOneByQuery(
			QueryWrapper.create().eq("provider", GisConstants.PROVIDER_XYZ));
		Map<String, Object> data = new LinkedHashMap<>();
		data.put("available", xyz.available());
		data.put("allowedHosts", xyz.props().getAllowedHosts());
		data.put("usingDefault", row == null);
		GisXyzTemplate cur = xyzEffective(row);
		data.put("current", cur == null ? null : templateMap(cur));
		List<Map<String, Object>> presets = new ArrayList<>();
		GisXyzPresets.all().forEach((code, t) -> {
			Map<String, Object> m = templateMap(t);
			m.put("code", code);
			presets.add(m);
		});
		data.put("presets", presets);
		return R.data(data);
	}

	/** 测试取一张瓦片（走与正式取图同一条受限链路），可带未保存的草稿配置 */
	@SaCheckPermission(GisConstants.PERM_PROVIDER_SAVE)
	@RepeatSubmit
	@PostMapping("/provider/test")
	public R<Map<String, Object>> test(@RequestBody GisMapProvider body) {
		moduleService.requireEnabled();
		if (body == null || !GisConstants.PROVIDER_XYZ.equals(body.getProvider())) {
			throw new ServiceException("目前只支持测试自定义瓦片");
		}
		if (!xyz.available()) {
			throw new ServiceException(GisConstants.MSG_XYZ_DISABLED);
		}
		GisMapProvider row = providerMapper.selectOneByQuery(
			QueryWrapper.create().eq("provider", GisConstants.PROVIDER_XYZ));
		String draft = blankToNull(body.getExtraJson());
		GisXyzTemplate t = draft != null ? xyz.parse(xyz.normalize(draft)) : xyzEffective(row);
		if (t == null) {
			throw new ServiceException("请先选择预设或填写瓦片地址");
		}
		String key = blankToNull(body.getApiKey());
		// 草稿模板不带出已存密钥，避免把密钥拼到另一份地址上
		if (key == null && row != null && draft == null) {
			key = row.getApiKey();
		}
		Map<String, Object> data = new LinkedHashMap<>();
		long t0 = System.currentTimeMillis();
		try {
			GisUpstreamHttp.LimitedBytes resp = xyz.fetch(t, 1, 1, 0, key);
			String ctype = resp.contentType() == null ? "" : resp.contentType();
			boolean ok = resp.status() == 200 && ctype.toLowerCase(java.util.Locale.ROOT).startsWith("image/");
			data.put("ok", ok);
			data.put("status", resp.status());
			data.put("bytes", resp.body().length);
			data.put("message", ok ? "瓦片可正常获取" : "上游返回 " + resp.status() + "（" + ctype + "）");
		} catch (ServiceException e) {
			data.put("ok", false);
			data.put("message", e.getMessage());
		} catch (Exception e) {
			data.put("ok", false);
			data.put("message", "连接上游失败或超时");
		}
		data.put("millis", System.currentTimeMillis() - t0);
		return R.data(data);
	}

	private GisXyzTemplate xyzEffective(GisMapProvider row) {
		if (!xyz.available()) {
			return null;
		}
		try {
			return xyz.effective(row);
		} catch (ServiceException e) {
			return null;
		}
	}

	private static Map<String, Object> templateMap(GisXyzTemplate t) {
		Map<String, Object> m = new LinkedHashMap<>();
		m.put("url", t.url());
		m.put("subdomains", t.subdomains());
		m.put("attribution", t.attribution());
		m.put("maxZoom", t.maxZoom());
		return m;
	}

	private static String vendorAttribution(String code) {
		return switch (code) {
			case "tianditu" -> "© 天地图";
			case "amap" -> "© 高德地图";
			case "baidu" -> "© 百度地图";
			case "google" -> "© Google";
			default -> "";
		};
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
			case "xyz" -> "自定义瓦片";
			default -> "谷歌";
		};
	}

	private void maskSecrets(GisMapProvider row) {
		row.setApiKey(null);
		row.setSecret(null);
	}
}
