package com.mugsun.boot.system.controller;

import cn.dev33.satoken.annotation.SaCheckLogin;
import cn.dev33.satoken.annotation.SaCheckPermission;
import com.mugsun.boot.system.entity.SysReport;
import com.mugsun.boot.system.mapper.SysReportMapper;
import com.mugsun.core.tool.api.R;
import com.mugsun.core.tool.exception.ServiceException;
import com.mybatisflex.core.query.QueryWrapper;
import com.mybatisflex.core.row.Db;
import com.mybatisflex.core.row.Row;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * 报表：报表定义（设计）+ 内置数据集预览。
 * 契合精简栈——基于 MyBatis-Flex 聚合查询，无重报表引擎依赖；可视化渲染由前端承担。
 */
@RestController
@RequestMapping("/system/report")
@SaCheckLogin
public class SysReportController {

	/** 内置数据集：key -> 聚合 SQL（预置白名单，杜绝任意 SQL 注入） */
	private static String datasetSql(String key) {
		String u = com.mugsun.boot.config.BizTables.of("sys_user");
		return switch (key) {
			case "user_status" -> "SELECT CASE status WHEN 1 THEN '启用' ELSE '停用' END AS \"name\", count(*) AS \"value\" "
				+ "FROM " + u + " WHERE is_deleted = 0 GROUP BY status";
			case "tenant_user" -> "SELECT COALESCE(tenant_id, '未分配') AS \"name\", count(*) AS \"value\" "
				+ "FROM " + u + " WHERE is_deleted = 0 GROUP BY tenant_id";
			default -> null;
		};
	}

	/** 数据集展示名（供设计端下拉） */
	private static final Map<String, String> DATASET_LABELS = Map.of(
		"user_status", "用户状态分布",
		"tenant_user", "租户用户数"
	);

	private final SysReportMapper reportMapper;

	public SysReportController(SysReportMapper reportMapper) {
		this.reportMapper = reportMapper;
	}

	/** 可选内置数据集列表 */
	@GetMapping("/datasets")
	public R<List<Map<String, String>>> datasets() {
		return R.data(DATASET_LABELS.entrySet().stream()
			.map(e -> Map.of("key", e.getKey(), "label", e.getValue()))
			.toList());
	}

	@GetMapping("/list")
	public R<List<SysReport>> list() {
		return R.data(reportMapper.selectListByQuery(QueryWrapper.create().orderBy("id", false)));
	}

	@GetMapping("/detail")
	public R<SysReport> detail(@RequestParam Long id) {
		return R.data(reportMapper.selectOneById(id));
	}

	/** 报表设计：保存报表定义 */
	@SaCheckPermission("sys:report:save")
	@PostMapping("/submit")
	public R<Void> submit(@RequestBody SysReport report) {
		if (report.getId() == null) {
			reportMapper.insertSelective(report);
		} else {
			reportMapper.update(report);
		}
		return R.success("操作成功");
	}

	@SaCheckPermission("sys:report:remove")
	@PostMapping("/remove/{id}")
	public R<Void> remove(@PathVariable Long id) {
		reportMapper.deleteById(id);
		return R.success("删除成功");
	}

	/** 报表预览：按报表定义的内置数据集执行聚合，返回可视化数据 */
	@GetMapping("/preview")
	public R<List<Row>> preview(@RequestParam Long id) {
		SysReport report = reportMapper.selectOneById(id);
		if (report == null) {
			throw new ServiceException("报表不存在");
		}
		String sql = datasetSql(report.getReportKey());
		if (sql == null) {
			throw new ServiceException("未知数据集：" + report.getReportKey());
		}
		return R.data(Db.selectListBySql(sql));
	}

	/** 数据集预览：按内置数据集 key 执行聚合（多图表仪表盘按图逐个取数） */
	@GetMapping("/preview-dataset")
	public R<List<Row>> previewDataset(@RequestParam String key) {
		String sql = datasetSql(key);
		if (sql == null) {
			throw new ServiceException("未知数据集：" + key);
		}
		return R.data(Db.selectListBySql(sql));
	}
}
