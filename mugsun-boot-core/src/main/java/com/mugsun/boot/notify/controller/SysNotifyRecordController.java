package com.mugsun.boot.notify.controller;

import cn.dev33.satoken.annotation.SaCheckLogin;
import cn.dev33.satoken.annotation.SaCheckPermission;
import com.mugsun.boot.notify.entity.SysNotifyRecord;
import com.mugsun.boot.notify.mapper.SysNotifyRecordMapper;
import com.mugsun.core.tool.api.R;
import com.mybatisflex.core.paginate.Page;
import com.mybatisflex.core.query.QueryWrapper;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 通知发送流水查询：按租户隔离（流水表带 tenant_id，租户条件由租户工厂自动注入）。
 */
@RestController
@RequestMapping("/system/notify/record")
@SaCheckLogin
public class SysNotifyRecordController {

	private final SysNotifyRecordMapper recordMapper;

	public SysNotifyRecordController(SysNotifyRecordMapper recordMapper) {
		this.recordMapper = recordMapper;
	}

	@GetMapping("/page")
	@SaCheckPermission("sys:notify-record:list")
	public R<Page<SysNotifyRecord>> page(@RequestParam(defaultValue = "1") long pageNum,
										 @RequestParam(defaultValue = "10") long pageSize,
										 @RequestParam(required = false) Long batchId,
										 @RequestParam(required = false) String channel,
										 @RequestParam(required = false) String status,
										 @RequestParam(required = false) String templateCode) {
		QueryWrapper query = QueryWrapper.create().orderBy("id", false);
		if (batchId != null) {
			query.eq("batch_id", batchId);
		}
		if (channel != null && !channel.isBlank()) {
			query.eq("channel", channel);
		}
		if (status != null && !status.isBlank()) {
			query.eq("status", status);
		}
		if (templateCode != null && !templateCode.isBlank()) {
			query.eq("template_code", templateCode);
		}
		return R.data(recordMapper.paginate(pageNum, pageSize, query));
	}

	@GetMapping("/detail")
	@SaCheckPermission("sys:notify-record:list")
	public R<SysNotifyRecord> detail(@RequestParam Long id) {
		return R.data(recordMapper.selectOneById(id));
	}
}
