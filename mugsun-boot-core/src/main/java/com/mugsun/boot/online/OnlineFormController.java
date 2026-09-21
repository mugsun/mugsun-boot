package com.mugsun.boot.online;

import cn.dev33.satoken.annotation.SaCheckLogin;
import cn.dev33.satoken.annotation.SaCheckPermission;
import com.mugsun.boot.gen.GenMetaService;
import com.mugsun.boot.gen.entity.GenTable;
import com.mugsun.core.tool.api.R;
import com.mybatisflex.core.paginate.Page;
import com.mybatisflex.core.row.Row;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * 在线（低代码）运行时：按统一元数据（gen_table/gen_column）对真实业务表泛化增删改查，改配置即时生效、不生成代码。
 */
@RestController
@RequestMapping("/system/online-form")
@SaCheckLogin
public class OnlineFormController {

	private final OnlineService onlineService;
	private final GenMetaService metaService;

	public OnlineFormController(OnlineService onlineService, GenMetaService metaService) {
		this.onlineService = onlineService;
		this.metaService = metaService;
	}

	/** 可在线渲染的表单列表（复用已导入元数据） */
	@SaCheckPermission("sys:online:list")
	@GetMapping("/forms")
	public R<List<GenTable>> forms() {
		return R.data(metaService.list());
	}

	/** 表单元数据（表 + 字段配置），前端据此运行时渲染 */
	@SaCheckPermission("sys:online:list")
	@GetMapping("/meta")
	public R<Map<String, Object>> meta(@RequestParam Long tableId) {
		return R.data(onlineService.meta(tableId));
	}

	/** 运行时分页查询（is_query 字段经 query 参数动态过滤） */
	@SaCheckPermission("sys:online:list")
	@GetMapping("/page")
	public R<Page<Row>> page(@RequestParam Long tableId,
							 @RequestParam(defaultValue = "1") long pageNum,
							 @RequestParam(defaultValue = "10") long pageSize,
							 @RequestParam Map<String, String> params) {
		return R.data(onlineService.page(tableId, pageNum, pageSize, params));
	}

	/** 运行时详情 */
	@SaCheckPermission("sys:online:list")
	@GetMapping("/detail")
	public R<Row> detail(@RequestParam Long tableId, @RequestParam String id) {
		return R.data(onlineService.detail(tableId, id));
	}

	/** 运行时保存（新增/编辑），落真实物理表 */
	@SaCheckPermission("sys:online:edit")
	@PostMapping("/save")
	public R<Void> save(@RequestParam Long tableId, @RequestBody Map<String, Object> data) {
		onlineService.save(tableId, data);
		return R.success("操作成功");
	}

	/** 运行时删除（逻辑/物理） */
	@SaCheckPermission("sys:online:edit")
	@PostMapping("/remove")
	public R<Void> remove(@RequestParam Long tableId, @RequestBody List<Long> ids) {
		onlineService.remove(tableId, ids);
		return R.success("删除成功");
	}
}
