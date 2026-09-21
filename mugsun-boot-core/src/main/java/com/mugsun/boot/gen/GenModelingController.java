package com.mugsun.boot.gen;

import cn.dev33.satoken.annotation.SaCheckLogin;
import cn.dev33.satoken.annotation.SaCheckPermission;
import com.mugsun.boot.gen.entity.GenColumn;
import com.mugsun.boot.gen.entity.GenTable;
import com.mugsun.core.tool.api.R;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * 动态建表 / 规则建模：元数据 → 物理表 DDL（建表/增量同步/强制重建）与规则解析候选建模。
 * 反向导入（既有表→元数据）复用 {@code GenController#importTable}（/system/gen/import）。
 */
@RestController
@RequestMapping("/system/gen")
@SaCheckLogin
public class GenModelingController {

	private final DdlService ddlService;
	private final AiModelService aiModelService;
	private final GenMetaService metaService;

	public GenModelingController(DdlService ddlService, AiModelService aiModelService, GenMetaService metaService) {
		this.ddlService = ddlService;
		this.aiModelService = aiModelService;
		this.metaService = metaService;
	}

	/** 预览将执行的 DDL（不落库）：物理表不存在→建表；存在→同步 */
	@SaCheckPermission("sys:gen:preview")
	@GetMapping("/ddl/preview")
	public R<List<String>> previewDdl(@RequestParam Long tableId,
									  @RequestParam(defaultValue = "false") boolean force) {
		return R.data(ddlService.preview(tableId, force));
	}

	/** 正向建表：按元数据 CREATE 物理表 */
	@SaCheckPermission("sys:gen:ddl")
	@PostMapping("/ddl/create")
	public R<Void> createTable(@RequestParam Long tableId) {
		ddlService.createTable(tableId);
		return R.success("建表成功");
	}

	/** 同步：增量 ADD/RENAME（保数据）或强制 DROP+CREATE（毁数据，force=true） */
	@SaCheckPermission("sys:gen:ddl")
	@PostMapping("/ddl/sync")
	public R<Void> syncDdl(@RequestParam Long tableId,
						   @RequestParam(defaultValue = "false") boolean force) {
		return R.success(ddlService.syncTable(tableId, force));
	}

	/** 规则建模：按格式描述 → 候选可编辑元数据（仅产出候选，不落库/不建表；基于规则解析，不支持自由中文描述） */
	@SaCheckPermission("sys:gen:list")
	@PostMapping("/ai/draft")
	public R<Map<String, Object>> aiDraft(@RequestBody DraftParam param) {
		return R.data(aiModelService.draft(param.description()));
	}

	/** 人工确认：持久化元数据；build=true 时建物理表（确认闸门，绝不自动落库；整体事务化，建表失败连带回滚元数据） */
	@org.springframework.transaction.annotation.Transactional(rollbackFor = Exception.class)
	@SaCheckPermission("sys:gen:ddl")
	@PostMapping("/ai/confirm")
	public R<Long> aiConfirm(@RequestBody ConfirmParam param) {
		if (param.table() != null) {
			ddlService.validateTableName(param.table().getTableName());
		}
		Long tableId = metaService.createDraft(param.table(), param.columns());
		if (param.build()) {
			ddlService.createTable(tableId);
		}
		return R.data(tableId);
	}

	/** 规则建模描述参数 */
	public record DraftParam(String description) {
	}

	/** 确认建模参数：表级 + 字段级候选元数据 + 是否立即建表 */
	public record ConfirmParam(GenTable table, List<GenColumn> columns, boolean build) {
	}
}
