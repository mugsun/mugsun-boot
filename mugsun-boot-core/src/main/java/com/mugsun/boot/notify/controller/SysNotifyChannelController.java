package com.mugsun.boot.notify.controller;

import cn.dev33.satoken.annotation.SaCheckLogin;
import cn.dev33.satoken.annotation.SaCheckPermission;
import com.mugsun.boot.notify.entity.SysNotifyChannel;
import com.mugsun.boot.notify.mapper.SysNotifyChannelMapper;
import com.mugsun.core.tool.api.R;
import com.mybatisflex.core.paginate.Page;
import com.mybatisflex.core.query.QueryWrapper;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 通知渠道配置管理：CRUD（库表驱动热更新，渠道发送时按行懒建/差异刷新，无需重启）。
 */
@RestController
@RequestMapping("/system/notify/channel")
@SaCheckLogin
public class SysNotifyChannelController {

	private final SysNotifyChannelMapper channelMapper;

	public SysNotifyChannelController(SysNotifyChannelMapper channelMapper) {
		this.channelMapper = channelMapper;
	}

	@GetMapping("/page")
	@SaCheckPermission("sys:notify-channel:list")
	public R<Page<SysNotifyChannel>> page(@RequestParam(defaultValue = "1") long pageNum,
										  @RequestParam(defaultValue = "10") long pageSize) {
		Page<SysNotifyChannel> page = channelMapper.paginate(pageNum, pageSize,
			QueryWrapper.create().orderBy("id", false));
		// 凭据不出管理端（secret 列查询已自动解密，置空防外泄）
		page.getRecords().forEach(c -> c.setSecret(null));
		return R.data(page);
	}

	@GetMapping("/detail")
	@SaCheckPermission("sys:notify-channel:list")
	public R<SysNotifyChannel> detail(@RequestParam Long id) {
		SysNotifyChannel channel = channelMapper.selectOneById(id);
		if (channel != null) {
			channel.setSecret(null);
		}
		return R.data(channel);
	}

	/** 新增/更新：secret 留空表示不修改凭据（编辑回显本就拿不到明文） */
	@PostMapping("/submit")
	@SaCheckPermission("sys:notify-channel:save")
	public R<Void> submit(@RequestBody SysNotifyChannel channel) {
		if (channel.getSecret() != null && channel.getSecret().isBlank()) {
			channel.setSecret(null);
		}
		if (channel.getId() == null) {
			channelMapper.insertSelective(channel);
		} else {
			channelMapper.update(channel);
		}
		return R.success("操作成功");
	}

	@PostMapping("/remove")
	@SaCheckPermission("sys:notify-channel:remove")
	public R<Void> remove(@RequestBody List<Long> ids) {
		ids.forEach(channelMapper::deleteById);
		return R.success("删除成功");
	}
}
