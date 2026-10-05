package com.mugsun.boot.ai.controller;

import cn.dev33.satoken.annotation.SaCheckLogin;
import cn.dev33.satoken.annotation.SaCheckPermission;
import com.mugsun.boot.ai.AiConstants;
import com.mugsun.boot.ai.entity.AiKnowledge;
import com.mugsun.boot.ai.entity.AiKnowledgeAsset;
import com.mugsun.boot.ai.entity.AiKnowledgeSegment;
import com.mugsun.boot.ai.service.AiKnowledgeBizService;
import com.mugsun.boot.common.repeat.RepeatSubmit;
import com.mugsun.core.tool.api.R;
import com.mugsun.core.tool.exception.ServiceException;
import com.mybatisflex.core.paginate.Page;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

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

	@RepeatSubmit
	@PostMapping("/submit")
	@SaCheckPermission(AiConstants.PERM_KB_SAVE)
	public R<AiKnowledge> submit(@RequestBody(required = false) AiKnowledge body) {
		boolean create = body == null || body.getId() == null;
		AiKnowledge saved = service.submit(body);
		R<AiKnowledge> result = R.data(saved);
		result.setMsg(create
			? "已创建。可以上传资料，也能先测一下能不能命中"
			: "已保存。列表会显示最新内容");
		return result;
	}

	@RepeatSubmit
	@PostMapping("/remove")
	@SaCheckPermission(AiConstants.PERM_KB_REMOVE)
	public R<Void> remove(@RequestParam(required = false) String ids,
						  @RequestBody(required = false) Map<String, Object> body) {
		List<AiKnowledge> removed = service.remove(parseIds(ids, body, "请先选择要删除的知识库"));
		if (removed.size() == 1) {
			return R.success("已删除「" + removed.get(0).getName() + "」。列表里不会再出现");
		}
		return R.success("已删除 " + removed.size() + " 个知识库。列表里不会再出现");
	}

	@RepeatSubmit
	@PostMapping("/copy")
	@SaCheckPermission(AiConstants.PERM_KB_COPY)
	public R<AiKnowledge> copy(@RequestBody(required = false) Map<String, Object> body) {
		if (body == null || body.get("id") == null || String.valueOf(body.get("id")).isBlank()) {
			throw new ServiceException("请先在列表里选择要复制的知识库");
		}
		AiKnowledge saved = service.copy(parseId(body.get("id")));
		R<AiKnowledge> result = R.data(saved);
		result.setMsg("已复制「" + saved.getName() + "」。列表里可以打开这份副本");
		return result;
	}

	@PostMapping("/test")
	@SaCheckPermission(AiConstants.PERM_KB_TEST)
	public R<Map<String, Object>> test(@RequestBody(required = false) Map<String, Object> body) {
		if (body == null || body.get("id") == null || String.valueOf(body.get("id")).isBlank()) {
			throw new ServiceException("请先在列表里选择要测试的知识库");
		}
		String query = body.get("query") == null ? "" : body.get("query").toString();
		Map<String, Object> data = service.test(parseId(body.get("id")), query);
		R<Map<String, Object>> result = R.data(data);
		result.setMsg(String.valueOf(data.get("message")));
		return result;
	}

	@RepeatSubmit
	@PostMapping("/status")
	@SaCheckPermission(AiConstants.PERM_KB_SAVE)
	public R<Void> status(@RequestBody(required = false) Map<String, Object> body) {
		if (body == null || body.get("id") == null) {
			throw new ServiceException("请先在列表里选择要改状态的知识库");
		}
		if (body.get("status") == null || !String.valueOf(body.get("status")).matches("[01]")) {
			throw new ServiceException("状态请选择启用或停用");
		}
		int status = Integer.parseInt(String.valueOf(body.get("status")));
		service.updateStatus(parseId(body.get("id")), status);
		return R.success(status == 1
			? "已启用。检索会用到这个知识库"
			: "已停用。检索不会再使用这个知识库");
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

	@RepeatSubmit
	@PostMapping("/asset/remove")
	@SaCheckPermission(AiConstants.PERM_KB_ASSET_REMOVE)
	public R<Void> removeAsset(@RequestParam(required = false) String ids,
							   @RequestBody(required = false) Map<String, Object> body) {
		List<AiKnowledgeAsset> removed = service.removeAssets(parseIds(ids, body, "请先选择要删除的资料"));
		if (removed.size() == 1) {
			String name = removed.get(0).getFileName();
			return R.success("已删除「" + (name == null || name.isBlank() ? "这份资料" : name) + "」。分段也会一起清掉");
		}
		return R.success("已删除 " + removed.size() + " 份资料。分段也会一起清掉");
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
	public R<List<Map<String, Object>>> hitTest(@RequestBody(required = false) Map<String, Object> body) {
		if (body == null || body.get("knowledgeId") == null) {
			throw new ServiceException("请先打开一个知识库再测试");
		}
		String query = body.get("query") == null ? "" : body.get("query").toString();
		int topK = 6;
		if (body.get("topK") != null) {
			String rawTop = String.valueOf(body.get("topK")).trim();
			if (!rawTop.matches("\\d{1,2}")) {
				throw new ServiceException("返回条数请填 1 到 20");
			}
			topK = Integer.parseInt(rawTop);
		}
		return R.data(service.hitTest(parseId(body.get("knowledgeId")), query, topK));
	}

	@PostMapping("/vectorize")
	@SaCheckPermission(AiConstants.PERM_KB_SEG_VECTOR)
	public R<Map<String, Object>> vectorize(@RequestBody(required = false) Map<String, Object> body) {
		if (body == null || body.get("knowledgeId") == null) {
			throw new ServiceException("请先打开一个知识库再向量化");
		}
		Long knowledgeId = parseId(body.get("knowledgeId"));
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

	private static List<Long> parseIds(String ids, Map<String, Object> body, String emptyMessage) {
		String raw = ids;
		if ((raw == null || raw.isBlank()) && body != null) {
			Object value = body.get("ids") != null ? body.get("ids") : body.get("id");
			raw = value == null ? null : String.valueOf(value);
		}
		if (raw == null || raw.isBlank() || "null".equals(raw)) {
			throw new ServiceException(emptyMessage);
		}
		List<Long> idList = new ArrayList<>();
		for (String part : raw.split(",")) {
			if (part == null || part.isBlank()) {
				continue;
			}
			idList.add(parseId(part));
		}
		if (idList.isEmpty()) {
			throw new ServiceException(emptyMessage);
		}
		return idList;
	}

	private static Long parseId(Object raw) {
		String text = String.valueOf(raw).trim();
		if (!text.matches("\\d{1,19}")) {
			throw new ServiceException("编号请填写数字，可以从列表里复制");
		}
		return Long.valueOf(text);
	}
}
