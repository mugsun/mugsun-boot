package com.mugsun.boot.monitor.controller;

import cn.dev33.satoken.annotation.SaCheckLogin;
import cn.dev33.satoken.annotation.SaCheckPermission;
import com.mugsun.boot.common.constant.MonitorConstants;
import com.mugsun.boot.monitor.entity.SysApiLog;
import com.mugsun.boot.monitor.mapper.SysApiLogMapper;
import com.mugsun.core.tool.api.R;
import com.mybatisflex.core.paginate.Page;
import com.mybatisflex.core.query.QueryWrapper;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 访问日志查询：全量请求流水（含慢接口标记），按租户隔离视图。
 */
@RestController
@RequestMapping("/system/api-log")
@SaCheckLogin
public class ApiLogController {

	private final SysApiLogMapper apiLogMapper;

	public ApiLogController(SysApiLogMapper apiLogMapper) {
		this.apiLogMapper = apiLogMapper;
	}

	@GetMapping("/page")
	@SaCheckPermission(MonitorConstants.PERM_API_LOG_LIST)
	public R<Page<SysApiLog>> page(@RequestParam(defaultValue = "1") long pageNum,
								   @RequestParam(defaultValue = "10") long pageSize,
								   @RequestParam(required = false) Integer slow,
								   @RequestParam(required = false) String traceId,
								   @RequestParam(required = false) String uri) {
		QueryWrapper query = QueryWrapper.create().orderBy("id", false);
		if (slow != null) {
			query.and("slow = ?", slow);
		}
		if (traceId != null && !traceId.isBlank()) {
			query.and("trace_id = ?", traceId);
		}
		if (uri != null && !uri.isBlank()) {
			query.and("request_uri like ?", "%" + uri + "%");
		}
		return R.data(apiLogMapper.paginate(pageNum, Math.min(pageSize, 500), query));
	}

	@GetMapping("/detail")
	@SaCheckPermission(MonitorConstants.PERM_API_LOG_LIST)
	public R<SysApiLog> detail(@RequestParam Long id) {
		return R.data(apiLogMapper.selectOneById(id));
	}
}
