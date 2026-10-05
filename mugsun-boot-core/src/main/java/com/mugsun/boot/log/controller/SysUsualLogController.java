package com.mugsun.boot.log.controller;

import cn.dev33.satoken.annotation.SaCheckLogin;
import cn.dev33.satoken.annotation.SaCheckPermission;
import com.mugsun.boot.log.entity.SysUsualLog;
import com.mugsun.boot.log.mapper.SysUsualLogMapper;
import com.mugsun.boot.tenant.TenantContext;
import com.mugsun.core.tool.api.R;
import com.mugsun.core.tool.exception.ServiceException;
import com.mybatisflex.core.paginate.Page;
import com.mybatisflex.core.query.QueryWrapper;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Set;

/**
 * 通用日志查询。平台管理员可看全部租户，其余只能看本租户。
 */
@RestController
@RequestMapping("/system/usual-log")
@SaCheckLogin
public class SysUsualLogController {

	private static final Set<String> LEVELS = Set.of("info", "debug", "warn", "error");

	private final SysUsualLogMapper logMapper;

	public SysUsualLogController(SysUsualLogMapper logMapper) {
		this.logMapper = logMapper;
	}

	@GetMapping("/page")
	@SaCheckPermission("sys:usual-log:list")
	public R<Page<SysUsualLog>> page(@RequestParam(defaultValue = "1") long pageNum,
									 @RequestParam(defaultValue = "10") long pageSize,
									 @RequestParam(required = false) String level,
									 @RequestParam(required = false) String logId,
									 @RequestParam(required = false) String uri) {
		if (level != null && !level.isBlank() && !LEVELS.contains(level.trim())) {
			throw new ServiceException("日志级别请选择信息、调试、警告或错误");
		}
		if (logId != null && logId.trim().length() > 64) {
			throw new ServiceException("业务编号请控制在 64 字以内");
		}
		QueryWrapper query = QueryWrapper.create().orderBy("id", false);
		if (level != null && !level.isBlank()) {
			query.eq("log_level", level.trim());
		}
		if (logId != null && !logId.isBlank()) {
			query.eq("log_id", logId.trim());
		}
		if (uri != null && !uri.isBlank()) {
			query.like("request_uri", uri.trim());
		}
		Page<SysUsualLog> page = TenantContext.isPlatformSuperAdmin()
			? TenantContext.ignore(() -> logMapper.paginate(pageNum, Math.min(pageSize, 100), query))
			: logMapper.paginate(pageNum, Math.min(pageSize, 100), query);
		return R.data(page);
	}

	@GetMapping("/detail")
	@SaCheckPermission("sys:usual-log:list")
	public R<SysUsualLog> detail(@RequestParam(required = false) Long id) {
		if (id == null) {
			throw new ServiceException("请从列表中选择一条通用日志后再查看");
		}
		SysUsualLog row = TenantContext.isPlatformSuperAdmin()
			? TenantContext.ignore(() -> logMapper.selectOneById(id))
			: logMapper.selectOneById(id);
		if (row == null) {
			throw new ServiceException("这条通用日志不存在，或属于其他租户。请刷新列表后再看");
		}
		return R.data(row);
	}
}
