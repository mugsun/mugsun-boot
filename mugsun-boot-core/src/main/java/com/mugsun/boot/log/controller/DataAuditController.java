package com.mugsun.boot.log.controller;

import cn.dev33.satoken.annotation.SaCheckLogin;
import cn.dev33.satoken.annotation.SaCheckPermission;
import com.mugsun.boot.log.entity.SysDataAudit;
import com.mugsun.boot.log.mapper.SysDataAuditMapper;
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
 * 数据变更记录：按业务对象、操作类型和请求路径查看，详情含字段差异和请求来源。
 */
@RestController
@RequestMapping("/system/data-audit")
@SaCheckLogin
public class DataAuditController {

	private static final Set<String> ACTIONS = Set.of("create", "update", "delete");

	private final SysDataAuditMapper auditMapper;

	public DataAuditController(SysDataAuditMapper auditMapper) {
		this.auditMapper = auditMapper;
	}

	@GetMapping("/page")
	@SaCheckPermission("sys:data-audit:list")
	public R<Page<SysDataAudit>> page(@RequestParam(defaultValue = "1") long pageNum,
									  @RequestParam(defaultValue = "10") long pageSize,
									  @RequestParam(required = false) String bizTable,
									  @RequestParam(required = false) String action,
									  @RequestParam(required = false) String requestPath) {
		if (bizTable != null && bizTable.trim().length() > 64) {
			throw new ServiceException("业务对象请控制在 64 字以内");
		}
		if (action != null && !action.isBlank() && !ACTIONS.contains(action.trim())) {
			throw new ServiceException("操作类型请选择新增、修改或删除");
		}
		if (requestPath != null && requestPath.trim().length() > 255) {
			throw new ServiceException("请求路径请控制在 255 字以内");
		}
		QueryWrapper query = QueryWrapper.create()
			.select("id", "biz_table", "biz_id", "operator", "change_content", "action",
				"http_method", "request_path", "client_ip", "create_time")
			.orderBy("id", false);
		if (bizTable != null && !bizTable.isBlank()) {
			query.eq("biz_table", bizTable.trim());
		}
		if (action != null && !action.isBlank()) {
			query.eq("action", action.trim());
		}
		if (requestPath != null && !requestPath.isBlank()) {
			query.like("request_path", requestPath.trim());
		}
		return R.data(auditMapper.paginate(pageNum, pageSize, query));
	}

	@GetMapping("/detail")
	@SaCheckPermission("sys:data-audit:list")
	public R<SysDataAudit> detail(@RequestParam(required = false) Long id) {
		if (id == null) {
			throw new ServiceException("请从列表中选择一条变更记录后再查看");
		}
		SysDataAudit row = auditMapper.selectOneById(id);
		if (row == null) {
			throw new ServiceException("这条变更记录不存在，或已被清理。请刷新列表后再看");
		}
		return R.data(row);
	}
}
