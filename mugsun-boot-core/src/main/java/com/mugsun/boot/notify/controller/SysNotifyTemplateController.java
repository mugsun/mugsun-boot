package com.mugsun.boot.notify.controller;

import cn.dev33.satoken.annotation.SaCheckLogin;
import cn.dev33.satoken.annotation.SaCheckPermission;
import com.mugsun.boot.common.constant.NotifyConstants;
import com.mugsun.boot.notify.NotifyTemplateRenderer;
import com.mugsun.boot.notify.entity.SysNotifyTemplate;
import com.mugsun.boot.notify.mapper.SysNotifyTemplateMapper;
import com.mugsun.core.tool.api.R;
import com.mybatisflex.core.paginate.Page;
import com.mybatisflex.core.query.QueryWrapper;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 统一通知模板管理：CRUD；保存期由渲染器抽取 ${key} 占位落 required_params。
 */
@RestController
@RequestMapping("/system/notify/template")
@SaCheckLogin
public class SysNotifyTemplateController {

	private final SysNotifyTemplateMapper templateMapper;
	private final NotifyTemplateRenderer renderer;

	public SysNotifyTemplateController(SysNotifyTemplateMapper templateMapper, NotifyTemplateRenderer renderer) {
		this.templateMapper = templateMapper;
		this.renderer = renderer;
	}

	@GetMapping("/page")
	@SaCheckPermission("sys:notify:list")
	public R<Page<SysNotifyTemplate>> page(@RequestParam(defaultValue = "1") long pageNum,
										   @RequestParam(defaultValue = "10") long pageSize) {
		return R.data(templateMapper.paginate(pageNum, pageSize, QueryWrapper.create().orderBy("id", false)));
	}

	@GetMapping("/detail")
	@SaCheckPermission("sys:notify:list")
	public R<SysNotifyTemplate> detail(@RequestParam Long id) {
		return R.data(templateMapper.selectOneById(id));
	}

	/** 新增/更新：required_params 由渲染器从 subject/content 现抽，不信任前端传入 */
	@PostMapping("/submit")
	@SaCheckPermission("sys:notify:save")
	public R<Void> submit(@RequestBody SysNotifyTemplate template) {
		template.setRequiredParams(String.join(NotifyConstants.SPLIT,
			renderer.extractParams(template.getSubject(), template.getContent())));
		if (template.getId() == null) {
			templateMapper.insertSelective(template);
		} else {
			templateMapper.update(template);
		}
		return R.success("操作成功");
	}

	@PostMapping("/remove")
	@SaCheckPermission("sys:notify:remove")
	public R<Void> remove(@RequestBody List<Long> ids) {
		ids.forEach(templateMapper::deleteById);
		return R.success("删除成功");
	}
}
