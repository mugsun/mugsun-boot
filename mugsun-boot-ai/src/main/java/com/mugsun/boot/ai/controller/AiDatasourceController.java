package com.mugsun.boot.ai.controller;

import cn.dev33.satoken.annotation.SaCheckLogin;
import cn.dev33.satoken.annotation.SaCheckPermission;
import com.mugsun.boot.ai.AiConstants;
import com.mugsun.boot.ai.entity.AiDatasource;
import com.mugsun.boot.ai.service.AiDatasourceBizService;
import com.mugsun.core.tool.api.R;
import com.mybatisflex.core.paginate.Page;
import org.springframework.web.bind.annotation.*;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/system/ai/datasource")
@SaCheckLogin
public class AiDatasourceController {

	private final AiDatasourceBizService service;

	public AiDatasourceController(AiDatasourceBizService service) {
		this.service = service;
	}

	@GetMapping("/page")
	@SaCheckPermission(AiConstants.PERM_DATASOURCE_LIST)
	public R<Page<AiDatasource>> page(@RequestParam(defaultValue = "1") long pageNum,
									  @RequestParam(defaultValue = "20") long pageSize,
									  @RequestParam(required = false) String name) {
		return R.data(service.page(pageNum, pageSize, name));
	}

	@GetMapping("/detail/{id}")
	@SaCheckPermission(AiConstants.PERM_DATASOURCE_LIST)
	public R<AiDatasource> detail(@PathVariable Long id) {
		return R.data(service.detail(id));
	}

	@PostMapping("/submit")
	@SaCheckPermission(AiConstants.PERM_DATASOURCE_SAVE)
	public R<AiDatasource> submit(@RequestBody AiDatasource body) {
		return R.data(service.submit(body));
	}

	@PostMapping("/remove")
	@SaCheckPermission(AiConstants.PERM_DATASOURCE_REMOVE)
	public R<Void> remove(@RequestParam(required = false) String ids,
						 @RequestBody(required = false) Map<String, Object> body) {
		service.remove(com.mugsun.boot.ai.support.AiIds.parse(ids, body));
		return R.success("删除成功");
	}

	@PostMapping("/test")
	@SaCheckPermission(AiConstants.PERM_DATASOURCE_TEST)
	public R<Map<String, Object>> test(@RequestParam(required = false) Long id,
										@RequestBody(required = false) Map<String, Object> body) {
		Long real = id;
		if (real == null && body != null && body.get("id") != null) {
			real = Long.valueOf(String.valueOf(body.get("id")));
		}
		if (real == null) {
			throw new com.mugsun.core.tool.exception.ServiceException("缺少数据源 id");
		}
		return R.data(service.test(real));
	}

	@GetMapping("/tables")
	@SaCheckPermission(AiConstants.PERM_DATASOURCE_LIST)
	public R<List<String>> tables(@RequestParam Long id) {
		return R.data(service.tables(id));
	}
}
