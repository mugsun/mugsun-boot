package com.mugsun.boot.gen;

import cn.dev33.satoken.annotation.SaCheckLogin;
import cn.dev33.satoken.annotation.SaCheckPermission;
import com.mugsun.boot.common.repeat.RepeatSubmit;
import com.mugsun.boot.gen.entity.GenDatasource;
import com.mugsun.boot.log.OperationLog;
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
 * 代码生成数据源。可以登记多套库，口令不回传。
 */
@RestController
@RequestMapping("/system/gen-datasource")
@SaCheckLogin
public class GenDatasourceController {

	private final GenDatasourceService datasourceService;

	public GenDatasourceController(GenDatasourceService datasourceService) {
		this.datasourceService = datasourceService;
	}

	@GetMapping("/page")
	@SaCheckPermission("sys:gen-ds:list")
	public R<Map<String, Object>> page(@RequestParam(defaultValue = "1") long pageNum,
									   @RequestParam(defaultValue = "10") long pageSize,
									   @RequestParam(required = false) String name) {
		if (name != null && name.trim().length() > 64) {
			throw new ServiceException("数据源名称请控制在 64 字以内");
		}
		Page<GenDatasource> page = datasourceService.page(pageNum, pageSize, name);
		Map<String, Object> body = new LinkedHashMap<>();
		body.put("records", page.getRecords().stream().map(GenDatasourceService::view).toList());
		body.put("totalRow", page.getTotalRow());
		body.put("pageNumber", page.getPageNumber());
		body.put("pageSize", page.getPageSize());
		return R.data(body);
	}

	@GetMapping("/options")
	@SaCheckPermission("sys:gen:list")
	public R<List<Map<String, Object>>> options() {
		List<Map<String, Object>> list = new ArrayList<>();
		for (GenDatasource row : datasourceService.listConnected()) {
			list.add(GenDatasourceService.option(row));
		}
		return R.data(list);
	}

	@PostMapping("/save")
	@SaCheckPermission("sys:gen-ds:save")
	@RepeatSubmit(message = "请勿重复保存。请刷新列表确认这套数据源是否已经记下")
	@OperationLog("保存代码生成数据源")
	public R<Map<String, Object>> save(@RequestBody Map<String, Object> body) {
		return R.data(datasourceService.save(
			longOf(body.get("id")),
			text(body.get("dsName")),
			text(body.get("jdbcUrl")),
			text(body.get("username")),
			text(body.get("password")),
			text(body.get("remark"))
		));
	}

	@PostMapping("/remove")
	@SaCheckPermission("sys:gen-ds:remove")
	@RepeatSubmit(message = "请勿重复删除。请刷新列表确认是否已经删掉")
	@OperationLog("删除代码生成数据源")
	public R<Void> remove(@RequestBody(required = false) Map<String, Object> body) {
		if (body == null) {
			throw new ServiceException("请先勾选要删除的数据源");
		}
		List<GenDatasource> rows = datasourceService.remove(datasourceIds(body.get("ids")));
		if (rows.size() == 1) {
			return R.success("已删除「" + rows.get(0).getDsName() + "」。代码生成里不能再选择这套数据源");
		}
		return R.success("已删除 " + rows.size() + " 套数据源。代码生成里不能再选择它们");
	}

	@PostMapping("/test")
	@SaCheckPermission("sys:gen-ds:test")
	@OperationLog("测试代码生成数据源")
	public R<Map<String, Object>> test(@RequestBody Map<String, Object> body) {
		return R.data(datasourceService.test(longOf(body.get("id"))));
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
			throw new ServiceException("请从列表中选择一套数据源后再操作");
		}
	}

	private static List<Long> datasourceIds(Object raw) {
		if (!(raw instanceof List<?> list) || list.isEmpty()) {
			throw new ServiceException("请先勾选要删除的数据源");
		}
		List<Long> ids = new ArrayList<>();
		for (Object item : list) {
			String text = item == null ? "" : String.valueOf(item).trim();
			if (!text.matches("\\d{1,19}")) {
				throw new ServiceException("数据源编号请填写数字，可以从列表里复制");
			}
			long id = Long.parseLong(text);
			if (!ids.contains(id)) {
				ids.add(id);
			}
		}
		return ids;
	}
}
