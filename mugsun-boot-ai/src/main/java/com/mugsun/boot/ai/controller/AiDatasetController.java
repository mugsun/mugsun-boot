package com.mugsun.boot.ai.controller;

import cn.dev33.satoken.annotation.SaCheckLogin;
import cn.dev33.satoken.annotation.SaCheckPermission;
import com.mugsun.boot.ai.AiConstants;
import com.mugsun.boot.ai.entity.AiDataset;
import com.mugsun.boot.ai.entity.AiDatasetTable;
import com.mugsun.boot.ai.entity.AiTerminology;
import com.mugsun.boot.ai.service.AiDatasetBizService;
import com.mugsun.core.tool.api.R;
import com.mybatisflex.core.paginate.Page;
import org.springframework.web.bind.annotation.*;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

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
	public R<AiDataset> detail(@PathVariable Long id) {
		return R.data(service.detail(id));
	}

	@PostMapping("/submit")
	@SaCheckPermission(AiConstants.PERM_DATASET_SAVE)
	public R<AiDataset> submit(@RequestBody AiDataset body) {
		return R.data(service.submit(body));
	}

	@PostMapping("/remove")
	@SaCheckPermission(AiConstants.PERM_DATASET_REMOVE)
	public R<Void> remove(@RequestParam String ids) {
		service.remove(Arrays.stream(ids.split(",")).filter(s -> !s.isBlank()).map(Long::valueOf).collect(Collectors.toList()));
		return R.success("删除成功");
	}

	@PostMapping("/table/save")
	@SaCheckPermission(AiConstants.PERM_DATASET_CONFIG)
	public R<AiDatasetTable> saveTable(@RequestBody AiDatasetTable body) {
		return R.data(service.saveTable(body));
	}

	@GetMapping("/table/list")
	@SaCheckPermission(AiConstants.PERM_DATASET_CONFIG)
	public R<List<AiDatasetTable>> tables(@RequestParam Long datasetId) {
		return R.data(service.tables(datasetId));
	}

	@PostMapping("/term/save")
	@SaCheckPermission(AiConstants.PERM_DATASET_CONFIG)
	public R<AiTerminology> saveTerm(@RequestBody AiTerminology body) {
		return R.data(service.saveTerm(body));
	}

	@PostMapping("/term/bind")
	@SaCheckPermission(AiConstants.PERM_DATASET_CONFIG)
	public R<Void> bindTerm(@RequestParam Long datasetId, @RequestParam Long terminologyId) {
		service.bindTerm(datasetId, terminologyId);
		return R.success("ok");
	}

	@PostMapping("/ask")
	@SaCheckPermission(AiConstants.PERM_DATASET_RUN)
	public R<Map<String, Object>> ask(@RequestBody Map<String, Object> body) {
		Long id = Long.valueOf(body.get("datasetId").toString());
		String q = body.get("question") == null ? "" : body.get("question").toString();
		return R.data(service.ask(id, q));
	}

	@PostMapping("/analyze")
	@SaCheckPermission(AiConstants.PERM_DATASET_ANALYZE)
	public R<Map<String, Object>> analyze(@RequestBody Map<String, Object> body) {
		return R.data(service.analyze(Long.valueOf(body.get("datasetId").toString()),
			body.get("question") == null ? "" : body.get("question").toString()));
	}

	@PostMapping("/predict")
	@SaCheckPermission(AiConstants.PERM_DATASET_PREDICT)
	public R<Map<String, Object>> predict(@RequestBody Map<String, Object> body) {
		return R.data(service.predict(Long.valueOf(body.get("datasetId").toString()),
			body.get("question") == null ? "" : body.get("question").toString()));
	}

	@GetMapping("/suggest")
	@SaCheckPermission(AiConstants.PERM_DATASET_RUN)
	public R<List<String>> suggest(@RequestParam Long id) {
		return R.data(service.suggest(id));
	}

	@PostMapping("/sql/rerun")
	@SaCheckPermission(AiConstants.PERM_DATASET_RUN)
	public R<Map<String, Object>> rerun(@RequestBody Map<String, Object> body) {
		Long id = Long.valueOf((body.get("datasetId") != null ? body.get("datasetId") : body.get("id")).toString());
		String sql = body.get("sql") == null ? "" : body.get("sql").toString();
		return R.data(service.rerunSql(id, sql));
	}
}
