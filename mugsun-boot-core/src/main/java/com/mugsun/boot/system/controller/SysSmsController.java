package com.mugsun.boot.system.controller;

import cn.dev33.satoken.annotation.SaCheckLogin;
import cn.dev33.satoken.annotation.SaCheckPermission;
import com.mugsun.boot.system.entity.SysSms;
import com.mugsun.boot.system.mapper.SysSmsMapper;
import com.mugsun.core.tool.api.R;
import com.mybatisflex.core.paginate.Page;
import com.mybatisflex.core.query.QueryWrapper;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 短信平台配置管理
 */
@RestController
@RequestMapping("/system/sms")
@SaCheckLogin
public class SysSmsController {

	private final SysSmsMapper smsMapper;

	public SysSmsController(SysSmsMapper smsMapper) {
		this.smsMapper = smsMapper;
	}

	@GetMapping("/page")
	public R<Page<SysSms>> page(@RequestParam(defaultValue = "1") long pageNum,
								@RequestParam(defaultValue = "10") long pageSize) {
		return R.data(smsMapper.paginate(pageNum, pageSize, QueryWrapper.create().orderBy("id", false)));
	}

	@GetMapping("/detail")
	public R<SysSms> detail(@RequestParam Long id) {
		return R.data(smsMapper.selectOneById(id));
	}

	@SaCheckPermission("sys:sms:save")
	@PostMapping("/submit")
	public R<Void> submit(@RequestBody SysSms sms) {
		if (sms.getId() == null) {
			smsMapper.insertSelective(sms);
		} else {
			smsMapper.update(sms);
		}
		return R.success("操作成功");
	}

	@SaCheckPermission("sys:sms:remove")
	@PostMapping("/remove")
	public R<Void> remove(@RequestBody List<Long> ids) {
		ids.forEach(smsMapper::deleteById);
		return R.success("删除成功");
	}

	/** 启用指定配置：同租户互斥，其余置禁用 */
	@SaCheckPermission("sys:sms:edit")
	@PostMapping("/enable/{id}")
	public R<Void> enable(@PathVariable Long id) {
		smsMapper.selectListByQuery(QueryWrapper.create().eq("status", 1)).forEach(s -> {
			s.setStatus(0);
			smsMapper.update(s);
		});
		SysSms target = smsMapper.selectOneById(id);
		if (target != null) {
			target.setStatus(1);
			smsMapper.update(target);
		}
		return R.success("已启用");
	}
}
