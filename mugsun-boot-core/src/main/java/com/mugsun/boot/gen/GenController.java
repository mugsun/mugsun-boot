package com.mugsun.boot.gen;

import cn.dev33.satoken.annotation.SaCheckLogin;
import cn.dev33.satoken.annotation.SaCheckPermission;
import com.mugsun.boot.gen.entity.GenColumn;
import com.mugsun.boot.gen.entity.GenTable;
import com.mugsun.core.tool.api.R;
import com.mugsun.core.tool.exception.ServiceException;
import org.springframework.core.env.Environment;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 在线代码生成：数据源信息 / 库表内省 / 元数据导入与字段级配置 / 增量同步 / 预览 / zip 下载。
 */
@RestController
@RequestMapping("/system/gen")
@SaCheckLogin
public class GenController {

	private final GenService genService;
	private final GenMetaService metaService;
	private final GenRenderService renderService;
	private final Environment env;

	public GenController(GenService genService, GenMetaService metaService, GenRenderService renderService, Environment env) {
		this.genService = genService;
		this.metaService = metaService;
		this.renderService = renderService;
		this.env = env;
	}

	/** 当前数据源信息（只读，密码不下发） */
	@SaCheckPermission("sys:gen:list")
	@GetMapping("/datasource")
	public R<Map<String, Object>> datasource() {
		Map<String, Object> ds = new LinkedHashMap<>();
		ds.put("name", "主数据源");
		// 数据源配置在 mybatis-flex.datasource.primary.*（本项目多数据源体系），非 spring.datasource.*
		ds.put("url", env.getProperty("mybatis-flex.datasource.primary.url"));
		ds.put("username", env.getProperty("mybatis-flex.datasource.primary.username"));
		ds.put("driver", env.getProperty("mybatis-flex.datasource.primary.driver-class-name"));
		return R.data(ds);
	}

	/** 数据库全部表元数据 */
	@SaCheckPermission("sys:gen:list")
	@GetMapping("/tables")
	public R<List<Map<String, Object>>> tables() {
		return R.data(genService.tables());
	}

	/** 导入库表为元数据配置（内省列 + 智能推断落 gen_table/gen_column），返回表配置 id */
	@SaCheckPermission("sys:gen:import")
	@PostMapping("/import")
	public R<Long> importTable(@RequestBody ImportParam param) {
		if (param.tableName() == null || param.tableName().isBlank()) {
			throw new ServiceException("表名不能为空");
		}
		return R.data(metaService.importTable(param.tableName(), param.moduleName(),
			param.basePackage(), param.tablePrefix(), param.author()));
	}

	/** 已导入的表配置列表 */
	@GetMapping("/list")
	@SaCheckPermission("sys:gen:list")
	public R<List<GenTable>> list() {
		return R.data(metaService.list());
	}

	/** 增量同步库表结构到元数据（保留已编辑的表级/字段级配置） */
	@SaCheckPermission("sys:gen:edit")
	@PostMapping("/sync")
	public R<Void> sync(@RequestParam Long tableId) {
		metaService.syncTable(tableId);
		return R.success("同步成功");
	}

	/** 读取某表元数据配置（表级 + 字段级） */
	@GetMapping("/meta")
	@SaCheckPermission("sys:gen:list")
	public R<Map<String, Object>> meta(@RequestParam Long tableId) {
		return R.data(metaService.config(tableId));
	}

	/** 保存表级 + 字段级在线配置 */
	@SaCheckPermission("sys:gen:edit")
	@PostMapping("/meta/save")
	public R<Void> saveMeta(@RequestBody MetaSaveParam param) {
		metaService.saveConfig(param.table(), param.columns());
		return R.success("配置已保存");
	}

	/** 按元数据预览生成的全栈产物（对齐平台规约），返回 文件类别→内容 */
	@SaCheckPermission("sys:gen:preview")
	@GetMapping("/preview-meta")
	public R<Map<String, String>> previewMeta(@RequestParam Long tableId) {
		return R.data(renderService.preview(tableId));
	}

	/** 下载生成产物 zip（路径按工程结构组织，解压即入目录） */
	@SaCheckPermission("sys:gen:preview")
	@GetMapping("/download")
	public void download(@RequestParam Long tableId, jakarta.servlet.http.HttpServletResponse response) throws java.io.IOException {
		byte[] zip = renderService.zip(tableId);
		response.setContentType("application/zip");
		response.setHeader("Content-Disposition", "attachment; filename=mugsun-gen-" + tableId + ".zip");
		response.getOutputStream().write(zip);
		response.flushBuffer();
	}

	/** 导入参数：表名、模块名、基础包、表前缀、作者 */
	public record ImportParam(String tableName, String moduleName, String basePackage, String tablePrefix, String author) {
	}

	/** 元数据保存参数：表级配置 + 字段级配置列表 */
	public record MetaSaveParam(GenTable table, List<GenColumn> columns) {
	}
}
