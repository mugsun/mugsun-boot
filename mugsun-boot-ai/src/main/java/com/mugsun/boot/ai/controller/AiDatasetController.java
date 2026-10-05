package com.mugsun.boot.ai.controller;

import cn.dev33.satoken.annotation.SaCheckLogin;
import cn.dev33.satoken.annotation.SaCheckPermission;
import com.mugsun.boot.ai.AiConstants;
import com.mugsun.boot.ai.entity.AiDataset;
import com.mugsun.boot.ai.entity.AiDatasetTable;
import com.mugsun.boot.ai.entity.AiTerminology;
import com.mugsun.boot.ai.service.AiDatasetBizService;
import com.mugsun.boot.common.repeat.RepeatSubmit;
import com.mugsun.core.tool.api.R;
import com.mugsun.core.tool.exception.ServiceException;
import com.mybatisflex.core.paginate.Page;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/system/ai/dataset")
@SaCheckLogin
public class AiDatasetController {

	private final AiDatasetBizService service;

	public AiDatasetController(AiDatasetBizService service) {
		this.service = service;
	}

	@GetMapping("/page")
	@SaCheckPermission(AiConstants.PERM_DATASET_LIST)
	public R<Page<AiDataset>> page(@RequestParam(defaultValue = "1") long pageNum,
								   @RequestParam(defaultValue = "20") long pageSize,
								   @RequestParam(required = false) String name) {
		return R.data(service.page(pageNum, pageSize, name));
	}

	@GetMapping("/detail/{id}")
	@SaCheckPermission(AiConstants.PERM_DATASET_LIST)
	public R<AiDataset> detail(@PathVariable String id) {
		return R.data(service.detail(parseId(id, "请先在列表里选择要查看的问数")));
	}

	@RepeatSubmit
	@PostMapping("/submit")
	@SaCheckPermission(AiConstants.PERM_DATASET_SAVE)
	public R<AiDataset> submit(@RequestBody(required = false) AiDataset body) {
		boolean create = body == null || body.getId() == null;
		AiDataset saved = service.submit(body);
		R<AiDataset> result = R.data(saved);
		result.setMsg(create
			? "已添加。接下来点配置，选好数据源和表就能问数"
			: "已保存。列表会显示最新内容");
		return result;
	}

	@RepeatSubmit
	@PostMapping("/remove")
	@SaCheckPermission(AiConstants.PERM_DATASET_REMOVE)
	public R<Void> remove(@RequestParam(required = false) String ids,
						 @RequestBody(required = false) Map<String, Object> body) {
		List<AiDataset> removed = service.remove(parseIds(ids, body));
		if (removed.size() == 1) {
			return R.success("已删除「" + removed.get(0).getName() + "」。配置的表也会一起去掉");
		}
		return R.success("已删除 " + removed.size() + " 个问数。配置的表也会一起去掉");
	}

	@RepeatSubmit
	@PostMapping("/copy")
	@SaCheckPermission(AiConstants.PERM_DATASET_SAVE)
	public R<AiDataset> copy(@RequestBody(required = false) Map<String, Object> body) {
		Object raw = body == null ? null : body.get("id");
		if (raw == null || String.valueOf(raw).isBlank()) {
			throw new ServiceException("请先在列表里选择要复制的问数");
		}
		AiDataset saved = service.copy(parseId(raw, "问数编号请填写数字，可以从列表里复制"));
		R<AiDataset> result = R.data(saved);
		result.setMsg("已复制为「" + saved.getName() + "」。表配置会一起带过去");
		return result;
	}

	@GetMapping("/config/tables")
	@SaCheckPermission(AiConstants.PERM_DATASET_CONFIG)
	public R<Map<String, Object>> config(@RequestParam(required = false) String id) {
		if (id == null || id.isBlank()) {
			throw new ServiceException("请先在列表里选择要配置的问数");
		}
		return R.data(service.config(parseId(id, "问数编号请填写数字，可以从列表里复制")));
	}

	@RepeatSubmit
	@PostMapping("/config/tables")
	@SaCheckPermission(AiConstants.PERM_DATASET_CONFIG)
	public R<Void> saveConfig(@RequestBody(required = false) Map<String, Object> body) {
		if (body == null) {
			throw new ServiceException("请先选择数据源，并至少选一张表");
		}
		Object rawId = body.get("id") != null ? body.get("id") : body.get("datasetId");
		if (rawId == null || String.valueOf(rawId).isBlank()) {
			throw new ServiceException("请先在列表里选择要配置的问数");
		}
		Object rawDs = body.get("datasourceId");
		Long datasourceId = rawDs == null || String.valueOf(rawDs).isBlank()
			? null : parseId(rawDs, "数据源编号请填写数字，可以从数据源列表里复制");
		service.saveConfig(parseId(rawId, "问数编号请填写数字，可以从列表里复制"), datasourceId,
			stringList(body.get("tables")), body.get("bizDesc") == null ? null : String.valueOf(body.get("bizDesc")));
		return R.success("已保存表配置。可以回到列表点问数");
	}

	@PostMapping("/table/save")
	@SaCheckPermission(AiConstants.PERM_DATASET_CONFIG)
	public R<AiDatasetTable> saveTable(@RequestBody AiDatasetTable body) {
		return R.data(service.saveTable(body));
	}

	@GetMapping("/table/list")
	@SaCheckPermission(AiConstants.PERM_DATASET_CONFIG)
	public R<List<AiDatasetTable>> tables(@RequestParam(required = false) String datasetId) {
		if (datasetId == null || datasetId.isBlank()) {
			throw new ServiceException("请先在列表里选择要查看表的问数");
		}
		return R.data(service.tables(parseId(datasetId, "问数编号请填写数字，可以从列表里复制")));
	}

	@PostMapping("/term/save")
	@SaCheckPermission(AiConstants.PERM_DATASET_CONFIG)
	public R<AiTerminology> saveTerm(@RequestBody AiTerminology body) {
		return R.data(service.saveTerm(body));
	}

	@PostMapping("/term/bind")
	@SaCheckPermission(AiConstants.PERM_DATASET_CONFIG)
	public R<Void> bindTerm(@RequestParam(required = false) String datasetId,
							@RequestParam(required = false) String terminologyId) {
		if (datasetId == null || datasetId.isBlank() || terminologyId == null || terminologyId.isBlank()) {
			throw new ServiceException("请先选择问数和术语");
		}
		service.bindTerm(parseId(datasetId, "问数编号请填写数字，可以从列表里复制"),
			parseId(terminologyId, "术语编号请填写数字，可以从列表里复制"));
		return R.success("已绑定。问数时会带上这条术语");
	}

	@PostMapping("/ask")
	@SaCheckPermission(AiConstants.PERM_DATASET_RUN)
	public R<Map<String, Object>> ask(@RequestBody(required = false) Map<String, Object> body) {
		return R.data(service.ask(datasetId(body), text(body, "question")));
	}

	@PostMapping("/analyze")
	@SaCheckPermission(AiConstants.PERM_DATASET_ANALYZE)
	public R<Map<String, Object>> analyze(@RequestBody(required = false) Map<String, Object> body) {
		return R.data(service.analyze(datasetId(body), text(body, "question")));
	}

	@PostMapping("/predict")
	@SaCheckPermission(AiConstants.PERM_DATASET_PREDICT)
	public R<Map<String, Object>> predict(@RequestBody(required = false) Map<String, Object> body) {
		return R.data(service.predict(datasetId(body), text(body, "question")));
	}

	@GetMapping("/suggest")
	@SaCheckPermission(AiConstants.PERM_DATASET_RUN)
	public R<List<String>> suggest(@RequestParam(required = false) String id) {
		if (id == null || id.isBlank()) {
			throw new ServiceException("请先在列表里选择要出建议的问数");
		}
		return R.data(service.suggest(parseId(id, "问数编号请填写数字，可以从列表里复制")));
	}

	@PostMapping("/sql/rerun")
	@SaCheckPermission(AiConstants.PERM_DATASET_RUN)
	public R<Map<String, Object>> rerun(@RequestBody(required = false) Map<String, Object> body) {
		return R.data(service.rerunSql(datasetId(body), text(body, "sql")));
	}

	private static Long datasetId(Map<String, Object> body) {
		if (body == null) {
			throw new ServiceException("请先在列表里选择问数");
		}
		Object raw = body.get("datasetId") != null ? body.get("datasetId") : body.get("id");
		if (raw == null || String.valueOf(raw).isBlank()) {
			throw new ServiceException("请先在列表里选择问数");
		}
		return parseId(raw, "问数编号请填写数字，可以从列表里复制");
	}

	private static String text(Map<String, Object> body, String key) {
		if (body == null || body.get(key) == null) {
			return "";
		}
		return String.valueOf(body.get(key));
	}

	@SuppressWarnings("unchecked")
	private static List<String> stringList(Object raw) {
		if (!(raw instanceof List<?> list)) {
			return List.of();
		}
		List<String> names = new ArrayList<>();
		for (Object item : list) {
			if (item != null) {
				names.add(String.valueOf(item));
			}
		}
		return names;
	}

	private static List<Long> parseIds(String ids, Map<String, Object> body) {
		String raw = ids;
		if ((raw == null || raw.isBlank()) && body != null) {
			Object value = body.get("ids") != null ? body.get("ids") : body.get("id");
			raw = value == null ? null : String.valueOf(value);
		}
		if (raw == null || raw.isBlank() || "null".equals(raw)) {
			throw new ServiceException("请先选择要删除的问数");
		}
		List<Long> idList = new ArrayList<>();
		for (String part : raw.split(",")) {
			if (part == null || part.isBlank()) {
				continue;
			}
			idList.add(parseId(part, "问数编号请填写数字，可以从列表里复制"));
		}
		if (idList.isEmpty()) {
			throw new ServiceException("请先选择要删除的问数");
		}
		return idList;
	}

	private static Long parseId(Object raw, String hint) {
		String text = String.valueOf(raw).trim();
		if (!text.matches("\\d{1,19}")) {
			throw new ServiceException(hint);
		}
		return Long.valueOf(text);
	}
}
