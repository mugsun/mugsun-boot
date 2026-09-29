package com.mugsun.boot.ai.controller;

import cn.dev33.satoken.annotation.SaCheckLogin;
import cn.dev33.satoken.annotation.SaCheckPermission;
import com.mugsun.boot.ai.AiConstants;
import com.mugsun.boot.ai.entity.AiKnowledge;
import com.mugsun.boot.ai.entity.AiKnowledgeAsset;
import com.mugsun.boot.ai.entity.AiKnowledgeSegment;
import com.mugsun.boot.ai.service.AiKnowledgeBizService;
import com.mugsun.core.tool.api.R;
import com.mybatisflex.core.paginate.Page;
import org.springframework.web.bind.annotation.*;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/system/ai/knowledge")
@SaCheckLogin
public class AiKnowledgeController {

	private final AiKnowledgeBizService service;

	public AiKnowledgeController(AiKnowledgeBizService service) {
		this.service = service;
	}

	@GetMapping("/page")
	@SaCheckPermission(AiConstants.PERM_KB_LIST)
	public R<Page<AiKnowledge>> page(@RequestParam(defaultValue = "1") long pageNum,
									 @RequestParam(defaultValue = "20") long pageSize,
									 @RequestParam(required = false) String name) {
		return R.data(service.page(pageNum, pageSize, name));
	}

	@GetMapping("/detail/{id}")
	@SaCheckPermission(AiConstants.PERM_KB_DETAIL)
	public R<AiKnowledge> detail(@PathVariable Long id) {
		return R.data(service.detail(id));
	}

	@PostMapping("/submit")
	@SaCheckPermission(AiConstants.PERM_KB_SAVE)
	public R<AiKnowledge> submit(@RequestBody AiKnowledge body) {
		return R.data(service.submit(body));
	}

	@PostMapping("/remove")
	@SaCheckPermission(AiConstants.PERM_KB_REMOVE)
	public R<Void> remove(@RequestParam(required = false) String ids,
						  @RequestBody(required = false) Map<String, Object> body) {
		String raw = ids;
		if ((raw == null || raw.isBlank()) && body != null && body.get("id") != null) {
			raw = body.get("id").toString();
		}
		if ((raw == null || raw.isBlank()) && body != null && body.get("ids") != null) {
			raw = body.get("ids").toString();
		}
		if (raw == null || raw.isBlank()) {
			return R.fail("缺少 ids");
		}
		service.remove(Arrays.stream(raw.split(",")).filter(s -> !s.isBlank()).map(Long::valueOf).collect(Collectors.toList()));
		return R.success("删除成功");
	}

	@PostMapping("/copy")
	@SaCheckPermission(AiConstants.PERM_KB_COPY)
	public R<AiKnowledge> copy(@RequestBody Map<String, Object> body) {
		Long id = Long.valueOf(body.get("id").toString());
		return R.data(service.copy(id));
	}

	@PostMapping("/test")
	@SaCheckPermission(AiConstants.PERM_KB_TEST)
	public R<Map<String, Object>> test(@RequestBody Map<String, Object> body) {
		Long id = Long.valueOf(body.get("id").toString());
		String query = body.get("query") == null ? "" : body.get("query").toString();
		return R.data(service.test(id, query));
	}

	@PostMapping("/status")
	@SaCheckPermission(AiConstants.PERM_KB_SAVE)
	public R<Void> status(@RequestBody Map<String, Object> body) {
		Long id = Long.valueOf(body.get("id").toString());
		Integer status = Integer.valueOf(body.get("status").toString());
		service.updateStatus(id, status);
		return R.success("ok");
	}

	@PostMapping("/asset/upload")
	@SaCheckPermission(AiConstants.PERM_KB_ASSET_UPLOAD)
	public R<AiKnowledgeAsset> upload(@RequestBody AiKnowledgeAsset body) {
		return R.data(service.uploadAsset(body));
	}

	@GetMapping("/asset/list")
	@SaCheckPermission(AiConstants.PERM_KB_DETAIL)
	public R<List<AiKnowledgeAsset>> assets(@RequestParam Long knowledgeId) {
		return R.data(service.assets(knowledgeId));
	}

	@PostMapping("/asset/remove")
	@SaCheckPermission(AiConstants.PERM_KB_ASSET_REMOVE)
	public R<Void> removeAsset(@RequestParam String ids) {
		service.removeAssets(Arrays.stream(ids.split(",")).filter(s -> !s.isBlank()).map(Long::valueOf).collect(Collectors.toList()));
		return R.success("删除成功");
	}

	@PostMapping("/asset/resegment")
	@SaCheckPermission(AiConstants.PERM_KB_ASSET_UPLOAD)
	public R<AiKnowledgeAsset> resegment(@RequestParam Long id) {
		return R.data(service.resegmentAsset(id));
	}

	@GetMapping("/segment/list")
	@SaCheckPermission(AiConstants.PERM_KB_DETAIL)
	public R<List<AiKnowledgeSegment>> segments(@RequestParam Long knowledgeId,
												@RequestParam(required = false) Long assetId) {
		return R.data(service.segments(knowledgeId, assetId));
	}

	@PostMapping("/segment/submit")
	@SaCheckPermission(AiConstants.PERM_KB_DETAIL)
	public R<AiKnowledgeSegment> saveSegment(@RequestBody AiKnowledgeSegment body) {
		return R.data(service.saveSegment(body));
	}

	@PostMapping("/segment/status")
	@SaCheckPermission(AiConstants.PERM_KB_DETAIL)
	public R<Void> segmentStatus(@RequestBody Map<String, Object> body) {
		Long id = Long.valueOf(String.valueOf(body.get("id")));
		Integer enabled = body.get("status") == null && body.get("enabled") == null ? null
			: Integer.valueOf(String.valueOf(body.get("status") != null ? body.get("status") : body.get("enabled")));
		service.segmentStatus(id, enabled);
		return R.success("ok");
	}

	@PostMapping("/hit-test")
	@SaCheckPermission(AiConstants.PERM_KB_HIT_TEST)
	public R<List<Map<String, Object>>> hitTest(@RequestBody Map<String, Object> body) {
		Long knowledgeId = Long.valueOf(body.get("knowledgeId").toString());
		String query = body.get("query") == null ? "" : body.get("query").toString();
		int topK = body.get("topK") == null ? 6 : Integer.parseInt(body.get("topK").toString());
		return R.data(service.hitTest(knowledgeId, query, topK));
	}

	@PostMapping("/vectorize")
	@SaCheckPermission(AiConstants.PERM_KB_SEG_VECTOR)
	public R<Map<String, Object>> vectorize(@RequestBody Map<String, Object> body) {
		Long knowledgeId = Long.valueOf(body.get("knowledgeId").toString());
		boolean rebuild = body.get("rebuild") != null && Boolean.parseBoolean(body.get("rebuild").toString());
		if (body.get("pause") != null && Boolean.parseBoolean(body.get("pause").toString())) {
			return R.fail("当前为同步向量化，不支持暂停；请直接开始或全量重建");
		}
		return R.data(service.vectorize(knowledgeId, rebuild));
	}

	@GetMapping("/vector-stats")
	@SaCheckPermission(AiConstants.PERM_KB_DETAIL)
	public R<Map<String, Object>> vectorStats(@RequestParam Long knowledgeId) {
		return R.data(service.vectorStats(knowledgeId));
	}
}
