package com.mugsun.boot.tablecolumn;

import cn.dev33.satoken.annotation.SaCheckLogin;
import cn.dev33.satoken.stp.StpUtil;
import cn.hutool.core.util.IdUtil;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mugsun.boot.common.repeat.RepeatSubmit;
import com.mugsun.boot.gen.DbDialects;
import com.mugsun.boot.gen.RuntimeSql;
import com.mugsun.boot.tablecolumn.entity.SysTableColumn;
import com.mugsun.boot.tablecolumn.mapper.SysTableColumnMapper;
import com.mugsun.core.tool.api.R;
import com.mugsun.core.tool.exception.ServiceException;
import com.mybatisflex.core.query.QueryWrapper;
import com.mybatisflex.core.row.Db;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.*;

import java.util.HashSet;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 表格自定义列持久化：按当前用户 + 表格标识存取列配置（顺序/显隐/宽），支持恢复默认。
 */
@RestController
@RequestMapping("/system/table-column")
@SaCheckLogin
public class TableColumnController {

	private static final Logger log = LoggerFactory.getLogger(TableColumnController.class);

	private static final Pattern TABLE_KEY = Pattern.compile("^[A-Za-z][A-Za-z0-9_-]{0,127}$");
	private static final Pattern COLUMN_KEY = Pattern.compile("^[A-Za-z_][A-Za-z0-9_]{0,63}$");
	private static final int MAX_COLUMNS = 80;

	private final SysTableColumnMapper columnMapper;
	private final ObjectMapper objectMapper;

	public TableColumnController(SysTableColumnMapper columnMapper, ObjectMapper objectMapper) {
		this.columnMapper = columnMapper;
		this.objectMapper = objectMapper;
	}

	/** 取当前用户某表的列配置，无则说明表格会用出厂列 */
	@GetMapping("/get")
	public R<SysTableColumn> get(@RequestParam(required = false) String tableKey) {
		requireTableKey(tableKey);
		SysTableColumn row = columnMapper.selectOneByQuery(query(tableKey));
		R<SysTableColumn> result = R.data(row);
		if (row == null) {
			result.setMsg("还没有保存过这张表的列。表格会按出厂列显示");
		}
		return result;
	}

	/** 保存（原子 upsert）：按 user_id + table_key 归并，存在则更新否则新增 */
	@RepeatSubmit
	@PostMapping("/save")
	public R<Void> save(@RequestBody(required = false) SysTableColumn body) {
		if (body == null) {
			throw new ServiceException("请先打开要调整列的表格。列配置是按这张表保存的");
		}
		String tableKey = requireTableKey(body.getTableKey());
		String json = body.getConfigJson();
		if (json == null || json.isBlank()) {
			throw new ServiceException("请先勾选要显示的列。至少留一列，也可以点恢复默认");
		}
		json = json.trim();
		if (json.length() > 20000) {
			throw new ServiceException("列配置太大。请少留几列，或点恢复默认后再调");
		}
		JsonNode node;
		try {
			node = objectMapper.readTree(json);
		} catch (Exception e) {
			throw new ServiceException("列配置不是合法内容。请回到列表重新勾选，或点恢复默认");
		}
		if (!node.isArray()) {
			throw new ServiceException("列配置需要是一组列。请回到列表重新勾选");
		}
		if (node.isEmpty()) {
			throw new ServiceException("至少保留一列。全部取消后表格会没有内容");
		}
		if (node.size() > MAX_COLUMNS) {
			throw new ServiceException("列太多。请少留几列，或点恢复默认后再调");
		}
		Set<String> keys = new HashSet<>();
		boolean anyVisible = false;
		for (JsonNode item : node) {
			if (!item.isObject()) {
				throw new ServiceException("有一列格式不对。请回到列表重新勾选，或点恢复默认");
			}
			String key = item.path("key").asText("").trim();
			if (!COLUMN_KEY.matcher(key).matches()) {
				throw new ServiceException("有一列标识不对。请回到列表重新勾选，或点恢复默认");
			}
			if (!keys.add(key)) {
				throw new ServiceException("有两列用了同一个标识。请回到列表重新勾选");
			}
			JsonNode visible = item.get("visible");
			if (visible == null || !visible.isBoolean()) {
				throw new ServiceException("每一列都要标明显示或隐藏。请回到列表重新勾选");
			}
			if (visible.booleanValue()) {
				anyVisible = true;
			}
			requireWidth(item.get("width"));
		}
		if (!anyVisible) {
			throw new ServiceException("请至少保留一列可见。全部隐藏后这张表会没有内容");
		}
		try {
			Db.updateBySql(RuntimeSql.upsertTableColumn(DbDialects.current()),
				IdUtil.getSnowflakeNextId(), StpUtil.getLoginIdAsLong(), tableKey, json);
		} catch (RuntimeException e) {
			log.warn("保存表格列失败: {}", e.getClass().getSimpleName());
			throw new ServiceException("这次没有保存成功。请回到列表重新勾选，或点恢复默认后再试");
		}
		return R.success("已保存 " + node.size() + " 列。刷新后这张表仍按这份配置显示");
	}

	/** 恢复默认：删除当前用户该表的配置行 */
	@RepeatSubmit
	@PostMapping("/reset/{tableKey}")
	public R<Void> reset(@PathVariable String tableKey) {
		requireTableKey(tableKey);
		int removed = columnMapper.deleteByQuery(query(tableKey));
		if (removed == 0) {
			return R.success("这张表本来就是默认列。刷新后仍按出厂列显示");
		}
		return R.success("已恢复默认。刷新后会按出厂列显示");
	}

	private QueryWrapper query(String tableKey) {
		return QueryWrapper.create()
				.eq("user_id", StpUtil.getLoginIdAsLong())
				.eq("table_key", tableKey);
	}

	private static String requireTableKey(String tableKey) {
		if (tableKey == null || tableKey.isBlank()) {
			throw new ServiceException("请先打开要调整列的表格。列配置是按这张表保存的");
		}
		String key = tableKey.trim();
		if (!TABLE_KEY.matcher(key).matches()) {
			throw new ServiceException("表格标识不对。请回到原来的列表再调整列");
		}
		return key;
	}

	private static void requireWidth(JsonNode width) {
		if (width == null || width.isNull()) {
			return;
		}
		int value;
		if (width.isIntegralNumber()) {
			value = width.intValue();
		} else if (width.isTextual() && width.asText().matches("\\d{2,4}")) {
			value = Integer.parseInt(width.asText());
		} else {
			throw new ServiceException("列宽请填 40 到 2000 之间的数字。请回到列表拖一下表头");
		}
		if (value < 40 || value > 2000) {
			throw new ServiceException("列宽请填 40 到 2000 之间的数字。请回到列表拖一下表头");
		}
	}
}
