package com.mugsun.boot.system.controller;

import cn.dev33.satoken.annotation.SaCheckLogin;
import cn.dev33.satoken.annotation.SaCheckPermission;
import com.mugsun.boot.log.OperationLog;
import com.mugsun.boot.system.entity.SysJobServer;
import com.mugsun.boot.system.service.JobServerRegistry;
import com.mugsun.core.tool.api.R;
import com.mugsun.core.tool.exception.ServiceException;
import com.mybatisflex.core.paginate.Page;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 任务服务。登记多套地址，保存和同步时向该地址登记应用。口令不回传。
 */
@RestController
@RequestMapping("/system/job-server")
@SaCheckLogin
public class SysJobServerController {

	private final JobServerRegistry registry;

	public SysJobServerController(JobServerRegistry registry) {
		this.registry = registry;
	}

	@GetMapping("/page")
	@SaCheckPermission("sys:job-server:list")
	public R<Map<String, Object>> page(@RequestParam(defaultValue = "1") long pageNum,
									   @RequestParam(defaultValue = "10") long pageSize,
									   @RequestParam(required = false) String name) {
		if (name != null && name.trim().length() > 64) {
			throw new ServiceException("服务名称请控制在 64 字以内");
		}
		Page<SysJobServer> page = registry.page(pageNum, pageSize, name);
		Map<String, Object> body = new LinkedHashMap<>();
		body.put("records", page.getRecords().stream().map(JobServerRegistry::view).toList());
		body.put("totalRow", page.getTotalRow());
		body.put("pageNumber", page.getPageNumber());
		body.put("pageSize", page.getPageSize());
		return R.data(body);
	}

	@GetMapping("/options")
	@SaCheckPermission("sys:job:list")
	public R<List<Map<String, Object>>> options() {
		List<Map<String, Object>> list = new ArrayList<>();
		for (SysJobServer row : registry.listAll()) {
			Map<String, Object> item = new LinkedHashMap<>();
			item.put("id", row.getId());
			item.put("serverName", row.getServerName());
			item.put("serverUrl", row.getServerUrl());
			list.add(item);
		}
		return R.data(list);
	}

	@PostMapping("/save")
	@SaCheckPermission("sys:job-server:save")
	@OperationLog("保存任务服务")
	public R<Map<String, Object>> save(@RequestBody Map<String, Object> body) {
		return R.data(registry.save(
			longOf(body.get("id")),
			text(body.get("serverName")),
			text(body.get("serverUrl")),
			text(body.get("appName")),
			text(body.get("password")),
			text(body.get("remark"))
		));
	}

	@PostMapping("/remove")
	@SaCheckPermission("sys:job-server:remove")
	@OperationLog("删除任务服务")
	public R<Void> remove(@RequestBody Map<String, Object> body) {
		registry.remove(longsOf(body.get("ids")));
		return R.success("已删除");
	}

	@PostMapping("/sync")
	@SaCheckPermission("sys:job-server:sync")
	@OperationLog("同步任务服务")
	public R<Map<String, Object>> sync(@RequestBody(required = false) Map<String, Object> body) {
		Long id = body == null ? null : longOf(body.get("id"));
		return R.data(registry.sync(id));
	}

	private static String text(Object raw) {
		return raw == null ? null : String.valueOf(raw);
	}

	private static Long longOf(Object raw) {
		if (raw == null || String.valueOf(raw).isBlank()) {
			return null;
		}
		try {
			return Long.valueOf(String.valueOf(raw).trim());
		} catch (NumberFormatException e) {
			throw new ServiceException("请从列表中选择一套任务服务后再操作");
		}
	}

	private static List<Long> longsOf(Object raw) {
		if (!(raw instanceof List<?> list)) {
			return List.of();
		}
		List<Long> ids = new ArrayList<>();
		for (Object item : list) {
			Long id = longOf(item);
			if (id != null) {
				ids.add(id);
			}
		}
		return ids;
	}
}
