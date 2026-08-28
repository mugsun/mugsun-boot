package com.mugsun.boot.log.controller;

import cn.dev33.satoken.annotation.SaCheckLogin;
import com.mugsun.boot.log.entity.SysDataAudit;
import com.mugsun.boot.log.mapper.SysDataAuditMapper;
import com.mugsun.core.tool.api.R;
import com.mybatisflex.core.paginate.Page;
import com.mybatisflex.core.query.QueryWrapper;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 数据变更记录查询：分页 + 详情（含字段级 diff change_content）。
 */
@RestController
@RequestMapping("/system/data-audit")
@SaCheckLogin
public class DataAuditController {

	private final SysDataAuditMapper auditMapper;

	public DataAuditController(SysDataAuditMapper auditMapper) {
		this.auditMapper = auditMapper;
	}

	@GetMapping("/page")
	public R<Page<SysDataAudit>> page(@RequestParam(defaultValue = "1") long pageNum,
									  @RequestParam(defaultValue = "10") long pageSize,
									  @RequestParam(required = false) String bizTable) {
		// 列表仅取摘要字段，before_data/after_data 大字段留待 detail 查
		QueryWrapper query = QueryWrapper.create()
			.select("id", "biz_table", "biz_id", "operator", "change_content", "create_time")
			.orderBy("id", false);
		if (bizTable != null && !bizTable.isBlank()) {
			query.eq("biz_table", bizTable);
		}
		return R.data(auditMapper.paginate(pageNum, pageSize, query));
	}

	@GetMapping("/detail")
	public R<SysDataAudit> detail(@RequestParam Long id) {
		return R.data(auditMapper.selectOneById(id));
	}
}
