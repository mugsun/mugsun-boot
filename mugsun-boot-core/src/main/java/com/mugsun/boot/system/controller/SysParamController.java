package com.mugsun.boot.system.controller;

import cn.dev33.satoken.annotation.SaCheckLogin;
import cn.dev33.satoken.annotation.SaCheckPermission;
import cn.dev33.satoken.annotation.SaMode;
import com.mugsun.boot.common.module.ModuleSwitch;
import com.mugsun.boot.common.repeat.RepeatSubmit;
import com.mugsun.boot.system.entity.SysParam;
import com.mugsun.boot.system.mapper.SysParamMapper;
import com.mugsun.boot.system.service.ParamService;
import com.mugsun.core.tool.api.R;
import com.mugsun.core.tool.exception.ServiceException;
import com.mybatisflex.core.paginate.Page;
import com.mybatisflex.core.query.QueryWrapper;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Locale;

/**
 * 系统参数管理。参数是平台全局数据，不按租户拆开。
 */
@RestController
@RequestMapping("/system/param")
@SaCheckLogin
public class SysParamController {

	private static final List<String> SENSITIVE_KEYWORDS = List.of(
		"password", "secret", "token", "accesskey", "apikey", "security", "auth"
	);

	private final SysParamMapper paramMapper;
	private final ParamService paramService;

	public SysParamController(SysParamMapper paramMapper, ParamService paramService) {
		this.paramMapper = paramMapper;
		this.paramService = paramService;
	}

	@SaCheckPermission(value = {"sys:param:save", "sys:param:remove"}, mode = SaMode.OR)
	@GetMapping("/page")
	public R<Page<SysParam>> page(@RequestParam(defaultValue = "1") long pageNum,
								  @RequestParam(defaultValue = "20") long pageSize,
								  @RequestParam(required = false) String paramName,
								  @RequestParam(required = false) String paramKey,
								  @RequestParam(required = false) String paramValue) {
		Page<SysParam> page = paramMapper.paginate(pageNum, Math.min(pageSize, 500), query(paramName, paramKey, paramValue));
		page.getRecords().forEach(this::mask);
		return R.data(page);
	}

	@SaCheckPermission(value = {"sys:param:save", "sys:param:remove"}, mode = SaMode.OR)
	@GetMapping("/list")
	public R<List<SysParam>> list(@RequestParam(required = false) String paramName,
								  @RequestParam(required = false) String paramKey,
								  @RequestParam(required = false) String paramValue) {
		List<SysParam> rows = paramMapper.selectListByQuery(query(paramName, paramKey, paramValue));
		rows.forEach(this::mask);
		return R.data(rows);
	}

	/** 按键取参数值。含口令或密钥的键不从这里返回原值。 */
	@SaCheckPermission("sys:param:save")
	@GetMapping("/value")
	public R<String> value(@RequestParam String paramKey) {
		if (paramKey == null || paramKey.isBlank()) {
			throw new ServiceException("请填写参数键");
		}
		if (sensitive(paramKey)) {
			throw new ServiceException("这个参数包含口令或密钥，这里不会显示原值。需要更换时，请到参数管理里编辑并填写新值");
		}
		return R.data(paramService.getValue(paramKey.trim()));
	}

	@SaCheckPermission(value = {"sys:param:save", "sys:param:remove"}, mode = SaMode.OR)
	@GetMapping("/detail")
	public R<SysParam> detail(@RequestParam Long id) {
		SysParam param = paramMapper.selectOneById(id);
		if (param == null) {
			throw new ServiceException("这条参数不存在，或已被删除。请刷新列表后再看");
		}
		mask(param);
		return R.data(param);
	}

	@RepeatSubmit(message = "请勿重复保存。请到列表里确认这条参数是否已经提交")
	@SaCheckPermission("sys:param:save")
	@PostMapping("/submit")
	public R<Void> submit(@RequestBody SysParam param) {
		if (param == null || param.getParamName() == null || param.getParamName().isBlank()) {
			throw new ServiceException("请填写参数名称");
		}
		if (param.getParamName().trim().length() > 128) {
			throw new ServiceException("参数名称请控制在 128 字以内");
		}
		param.setParamName(param.getParamName().trim());
		if (param.getParamKey() == null || !param.getParamKey().trim().matches("^[A-Za-z][A-Za-z0-9._-]{0,127}$")) {
			throw new ServiceException("参数键请使用字母开头，只能包含字母、数字、点、下划线或短横线");
		}
		param.setParamKey(param.getParamKey().trim());
		if (param.getRemark() != null && param.getRemark().length() > 255) {
			throw new ServiceException("备注请控制在 255 字以内");
		}
		ModuleSwitch.State switchState = checkModuleSwitch(param);
		if (param.getId() == null) {
			requireValue(param.getParamValue());
			param.setParamValue(param.getParamValue().trim());
			assertKeyFree(param.getParamKey(), null);
			paramMapper.insertSelective(param);
		} else {
			SysParam exist = paramMapper.selectOneById(param.getId());
			if (exist == null) {
				throw new ServiceException("这条参数不存在，或已被删除。请刷新列表后再编辑");
			}
			if (!exist.getParamKey().equals(param.getParamKey())) {
				throw new ServiceException("参数键创建后不能修改。请新增一条，再删除原来的");
			}
			if (param.getParamValue() == null || param.getParamValue().isBlank()) {
				if (!sensitive(exist.getParamKey())) {
					throw new ServiceException("请填写参数值");
				}
				param.setParamValue(exist.getParamValue());
			} else {
				if (param.getParamValue().trim().length() > 255) {
					throw new ServiceException("参数值请控制在 255 字以内");
				}
				param.setParamValue(param.getParamValue().trim());
			}
			paramMapper.update(param);
		}
		paramService.evict(param.getParamKey());
		if (switchState != null && switchState.deprecated()) {
			return R.success("已保存。" + ModuleSwitch.MSG_DEPRECATED);
		}
		return R.success("已保存。可以在列表里查看这条参数");
	}

	@RepeatSubmit(message = "请勿重复删除。请刷新列表确认这些参数是否还在")
	@SaCheckPermission("sys:param:remove")
	@PostMapping("/remove")
	public R<Void> remove(@RequestBody List<Long> ids) {
		if (ids == null || ids.isEmpty()) {
			throw new ServiceException("请先勾选要删除的参数");
		}
		int removed = 0;
		for (Long id : ids) {
			if (id == null) {
				continue;
			}
			SysParam exist = paramMapper.selectOneById(id);
			if (exist == null) {
				continue;
			}
			paramMapper.deleteById(id);
			paramService.evict(exist.getParamKey());
			removed++;
		}
		if (removed == 0) {
			throw new ServiceException("这些参数不存在，或已被删除。请刷新列表后再试");
		}
		return R.success("已删除。列表里不会再出现这些参数");
	}

	private QueryWrapper query(String paramName, String paramKey, String paramValue) {
		QueryWrapper query = QueryWrapper.create().orderBy("id", false);
		if (paramName != null && !paramName.isBlank()) {
			query.like("param_name", paramName.trim());
		}
		if (paramKey != null && !paramKey.isBlank()) {
			query.like("param_key", paramKey.trim());
		}
		if (paramValue != null && !paramValue.isBlank()) {
			query.like("param_value", paramValue.trim());
		}
		return query;
	}

	/** 模块开关键（gis/ai.module.enabled）只收 true/false（1/0 仍可保存但会提示已弃用），非法值拒收；值为空交给后面的必填校验 */
	private ModuleSwitch.State checkModuleSwitch(SysParam param) {
		String v = param.getParamValue();
		if (v == null || v.isBlank()) {
			return null;
		}
		ModuleSwitch.State state = ModuleSwitch.checkParam(param.getParamKey(), v);
		if (state != null && !state.legal()) {
			throw new ServiceException(ModuleSwitch.MSG_INVALID);
		}
		return state;
	}

	private void assertKeyFree(String paramKey, Long id) {
		QueryWrapper query = QueryWrapper.create().eq("param_key", paramKey);
		if (id != null) {
			query.ne("id", id);
		}
		if (paramMapper.selectCountByQuery(query) > 0) {
			throw new ServiceException("参数键已存在。请换一个键，或在列表里编辑原来那条");
		}
	}

	private void requireValue(String value) {
		if (value == null || value.isBlank()) {
			throw new ServiceException("请填写参数值");
		}
		if (value.trim().length() > 255) {
			throw new ServiceException("参数值请控制在 255 字以内");
		}
	}

	private void mask(SysParam param) {
		if (param == null) {
			return;
		}
		if (!sensitive(param.getParamKey())) {
			param.setSensitive(false);
			return;
		}
		param.setParamValue(null);
		param.setSensitive(true);
	}

	private static boolean sensitive(String paramKey) {
		if (paramKey == null) {
			return false;
		}
		String lower = paramKey.toLowerCase(Locale.ROOT);
		for (String keyword : SENSITIVE_KEYWORDS) {
			if (lower.contains(keyword)) {
				return true;
			}
		}
		return false;
	}
}
