package com.mugsun.boot.monitor.controller;

import cn.dev33.satoken.annotation.SaCheckLogin;
import cn.dev33.satoken.annotation.SaCheckPermission;
import com.mugsun.boot.common.constant.MonitorConstants;
import com.mugsun.boot.monitor.DbDocService;
import com.mugsun.core.tool.api.R;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 服务监控管理端：在线数据库文档（指标看板数据由前端直拉受鉴权的 /actuator/metrics）。
 */
@RestController
@RequestMapping("/system/monitor")
@SaCheckLogin
public class MonitorController {

	private final DbDocService dbDocService;

	public MonitorController(DbDocService dbDocService) {
		this.dbDocService = dbDocService;
	}

	/** 全库表/列/注释 markdown（在线查看 + 前端下载） */
	@GetMapping("/db-doc")
	@SaCheckPermission(MonitorConstants.PERM_DB_DOC)
	public R<String> dbDoc() {
		return R.data(dbDocService.markdown());
	}
}
