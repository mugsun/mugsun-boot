package com.mugsun.boot.system.controller;

import cn.dev33.satoken.annotation.SaCheckLogin;
import cn.dev33.satoken.annotation.SaCheckPermission;
import com.mugsun.boot.common.repeat.RepeatSubmit;
import com.mugsun.boot.system.entity.SysReport;
import com.mugsun.boot.system.mapper.SysReportMapper;
import com.mugsun.core.tool.api.R;
import com.mugsun.core.tool.exception.ServiceException;
import com.mybatisflex.core.query.QueryWrapper;
import com.mybatisflex.core.row.Db;
import com.mybatisflex.core.row.Row;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

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
			case "user_status" -> "SELECT CASE status WHEN 1 THEN '启用' ELSE '停用' END AS \"name\", CASE status WHEN 1 THEN 'enabled' ELSE 'disabled' END AS \"key\", count(*) AS \"value\" "
				+ "FROM " + u + " WHERE is_deleted = 0 GROUP BY status";
			case "tenant_user" -> "SELECT COALESCE(tenant_id, '未分配') AS \"name\", CASE WHEN tenant_id IS NULL THEN 'unassigned' ELSE NULL END AS \"key\", count(*) AS \"value\" "
				+ "FROM " + u + " WHERE is_deleted = 0 GROUP BY tenant_id";
			default -> null;
		};
	}

	/** 数据集展示名（供设计端下拉） */
	private static final Map<String, String> DATASET_LABELS = Map.of(
		"user_status", "用户状态分布",
		"tenant_user", "租户用户数"
	);

	private static final Pattern DATASET_IN_CHARTS = Pattern.compile("\"dataset\"\\s*:\\s*\"([a-z0-9_]{1,64})\"");

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
	public R<List<SysReport>> list(@RequestParam(required = false) String name) {
		QueryWrapper query = QueryWrapper.create().orderBy("id", false);
		if (name != null && !name.isBlank()) {
			query.like("report_name", name.trim());
		}
		return R.data(reportMapper.selectListByQuery(query));
	}

	@GetMapping("/detail")
	public R<SysReport> detail(@RequestParam Long id) {
		return R.data(reportMapper.selectOneById(id));
	}

	/** 报表设计：保存报表定义 */
	@SaCheckPermission("sys:report:save")
	@RepeatSubmit(message = "请勿重复保存。请刷新列表确认这张报表是否已经记下")
	@PostMapping("/submit")
	public R<Void> submit(@RequestBody SysReport report) {
		prepare(report);
		if (report.getId() == null) {
			reportMapper.insertSelective(report);
		} else if (reportMapper.selectOneById(report.getId()) == null) {
			throw new ServiceException("这张报表不存在，或已被删除。请刷新列表后再编辑");
		} else {
			reportMapper.update(report);
		}
		return R.success("已保存。可以在列表里预览这张报表");
	}

	@SaCheckPermission("sys:report:remove")
	@RepeatSubmit(message = "请勿重复删除。请刷新列表确认是否已经删掉")
	@PostMapping("/remove/{id}")
	public R<Void> remove(@PathVariable Long id) {
		if (reportMapper.selectOneById(id) == null) {
			throw new ServiceException("这张报表不存在，或已被删除。请刷新列表后再试");
		}
		reportMapper.deleteById(id);
		return R.success("已删除。列表里不会再出现这张报表");
	}

	/** 批量删除。没勾选、或勾选的都已不在时说明下一步。 */
	@SaCheckPermission("sys:report:remove")
	@RepeatSubmit(message = "请勿重复删除。请刷新列表确认是否已经删掉")
	@PostMapping("/remove")
	public R<Void> removeBatch(@RequestBody Map<String, Object> body) {
		List<Long> ids = longsOf(body == null ? null : body.get("ids"));
		if (ids.isEmpty()) {
			throw new ServiceException("请先勾选要删除的报表");
		}
		int removed = 0;
		for (Long id : ids) {
			if (reportMapper.selectOneById(id) != null) {
				reportMapper.deleteById(id);
				removed++;
			}
		}
		if (removed == 0) {
			throw new ServiceException("这些报表不存在，或已被删除。请刷新列表后再试");
		}
		return R.success("已删除 " + removed + " 张报表");
	}

	/** 报表预览：按报表定义的内置数据集执行聚合，返回可视化数据 */
	@GetMapping("/preview")
	public R<List<Row>> preview(@RequestParam Long id) {
		SysReport report = reportMapper.selectOneById(id);
		if (report == null) {
			throw new ServiceException("这张报表不存在，或已被删除。请刷新列表后再预览");
		}
		String sql = datasetSql(report.getReportKey());
		if (sql == null) {
			throw new ServiceException("请从下拉里选择已有的数据集，再预览");
		}
		return R.data(Db.selectListBySql(sql));
	}

	/** 数据集预览：按内置数据集 key 执行聚合（多图表仪表盘按图逐个取数） */
	@GetMapping("/preview-dataset")
	public R<List<Row>> previewDataset(@RequestParam String key) {
		String sql = datasetSql(key);
		if (sql == null) {
			throw new ServiceException("请从下拉里选择已有的数据集，再预览");
		}
		return R.data(Db.selectListBySql(sql));
	}

	/** 名称、数据集和图表配置在落库前收口，避免空名称或任意数据集标识写进去。 */
	private void prepare(SysReport report) {
		if (report == null || report.getReportName() == null || report.getReportName().isBlank()) {
			throw new ServiceException("请填写报表名称");
		}
		String name = report.getReportName().trim();
		if (name.length() > 128) {
			throw new ServiceException("报表名称请控制在 128 字以内");
		}
		report.setReportName(name);
		List<String> datasets = datasetsOf(report);
		if (datasets.isEmpty()) {
			throw new ServiceException("请至少添加一张图，并从下拉里选择数据集");
		}
		for (String dataset : datasets) {
			if (datasetSql(dataset) == null) {
				throw new ServiceException("请从下拉里选择已有的数据集，再保存");
			}
		}
		report.setReportKey(datasets.get(0));
		SysReport same = reportMapper.selectOneByQuery(QueryWrapper.create().eq("report_name", name));
		if (same != null && (report.getId() == null || !same.getId().equals(report.getId()))) {
			throw new ServiceException("报表名称已存在，请换一个");
		}
	}

	private List<String> datasetsOf(SysReport report) {
		List<String> datasets = new ArrayList<>();
		if (report.getCharts() != null && !report.getCharts().isBlank()) {
			Matcher matcher = DATASET_IN_CHARTS.matcher(report.getCharts());
			while (matcher.find()) {
				datasets.add(matcher.group(1));
			}
			if (datasets.isEmpty()) {
				throw new ServiceException("请为每张图从下拉里选择数据集");
			}
			return datasets;
		}
		if (report.getReportKey() != null && !report.getReportKey().isBlank()) {
			datasets.add(report.getReportKey().trim());
		}
		return datasets;
	}

	private List<Long> longsOf(Object raw) {
		List<Long> ids = new ArrayList<>();
		if (!(raw instanceof List<?> list)) {
			return ids;
		}
		for (Object item : list) {
			if (item == null || item.toString().isBlank()) {
				continue;
			}
			try {
				ids.add(Long.valueOf(item.toString()));
			} catch (NumberFormatException ex) {
				throw new ServiceException("报表编号请填写数字，可以从列表里复制");
			}
		}
		return ids;
	}
}
