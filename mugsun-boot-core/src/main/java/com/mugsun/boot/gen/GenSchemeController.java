package com.mugsun.boot.gen;

import cn.dev33.satoken.annotation.SaCheckLogin;
import cn.dev33.satoken.annotation.SaCheckPermission;
import com.mugsun.boot.common.repeat.RepeatSubmit;
import com.mugsun.boot.gen.entity.GenScheme;
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
 * 生成方案。启用后，代码生成导入表时会带上这套模块、包名和作者。
 */
@RestController
@RequestMapping("/system/gen-scheme")
@SaCheckLogin
public class GenSchemeController {

	private final GenSchemeService schemeService;

	public GenSchemeController(GenSchemeService schemeService) {
		this.schemeService = schemeService;
	}

	@GetMapping("/page")
	@SaCheckPermission("sys:gen-scheme:list")
	public R<Map<String, Object>> page(@RequestParam(defaultValue = "1") long pageNum,
									   @RequestParam(defaultValue = "10") long pageSize,
									   @RequestParam(required = false) String name) {
		if (name != null && name.trim().length() > 64) {
			throw new ServiceException("方案名称请控制在 64 字以内");
		}
		Page<GenScheme> page = schemeService.page(pageNum, pageSize, name);
		Map<String, Object> body = new LinkedHashMap<>();
		body.put("records", page.getRecords().stream().map(GenSchemeService::view).toList());
		body.put("totalRow", page.getTotalRow());
		body.put("pageNumber", page.getPageNumber());
		body.put("pageSize", page.getPageSize());
		return R.data(body);
	}

	@GetMapping("/enabled")
	@SaCheckPermission("sys:gen:list")
	public R<Map<String, Object>> enabled() {
		GenScheme row = schemeService.enabledOrNull();
		return R.data(row == null ? null : GenSchemeService.view(row));
	}

	@PostMapping("/save")
	@SaCheckPermission("sys:gen-scheme:save")
	@RepeatSubmit(message = "请勿重复保存。请刷新列表确认这套方案是否已经记下")
	@OperationLog("保存生成方案")
	public R<Map<String, Object>> save(@RequestBody Map<String, Object> body) {
		return R.data(schemeService.save(
			longOf(body.get("id")),
			text(body.get("schemeName")),
			text(body.get("schemeCode")),
			text(body.get("moduleName")),
			text(body.get("basePackage")),
			text(body.get("tablePrefix")),
			text(body.get("author")),
			longOf(body.get("parentMenuId"), "上级菜单编号请填写数字，可以从菜单管理里复制，或留空"),
			text(body.get("remark"))
		));
	}

	@PostMapping("/enable")
	@SaCheckPermission("sys:gen-scheme:enable")
	@RepeatSubmit(message = "请勿重复启用。请刷新列表确认当前用的是哪一套")
	@OperationLog("启用生成方案")
	public R<Map<String, Object>> enable(@RequestBody Map<String, Object> body) {
		return R.data(schemeService.enable(longOf(body.get("id"))));
	}

	@PostMapping("/remove")
	@SaCheckPermission("sys:gen-scheme:remove")
	@RepeatSubmit(message = "请勿重复删除。请刷新列表确认是否已经删掉")
	@OperationLog("删除生成方案")
	public R<Void> remove(@RequestBody Map<String, Object> body) {
		schemeService.remove(longsOf(body.get("ids")));
		return R.success("已删除");
	}

	private static String text(Object raw) {
		return raw == null ? null : String.valueOf(raw);
	}

	private static Long longOf(Object raw) {
		return longOf(raw, "请从列表中选择一套生成方案后再操作");
	}

	private static Long longOf(Object raw, String invalid) {
		if (raw == null || String.valueOf(raw).isBlank()) {
			return null;
		}
		try {
			return Long.valueOf(String.valueOf(raw).trim());
		} catch (NumberFormatException e) {
			throw new ServiceException(invalid);
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
