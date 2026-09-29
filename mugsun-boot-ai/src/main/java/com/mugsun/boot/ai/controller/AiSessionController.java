package com.mugsun.boot.ai.controller;

import cn.dev33.satoken.annotation.SaCheckLogin;
import cn.dev33.satoken.annotation.SaCheckPermission;
import com.mugsun.boot.ai.AiConstants;
import com.mugsun.boot.ai.entity.AiMessage;
import com.mugsun.boot.ai.entity.AiSession;
import com.mugsun.boot.ai.service.AiChatBizService;
import com.mugsun.core.tool.api.R;
import com.mybatisflex.core.paginate.Page;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/system/ai/session")
@SaCheckLogin
public class AiSessionController {

	private final AiChatBizService chatService;

	public AiSessionController(AiChatBizService chatService) {
		this.chatService = chatService;
	}

	@GetMapping("/page")
	@SaCheckPermission(AiConstants.PERM_ASSISTANT_LIST)
	public R<Page<AiSession>> page(@RequestParam(defaultValue = "1") long pageNum,
								   @RequestParam(defaultValue = "20") long pageSize,
								   @RequestParam(required = false) String source) {
		return R.data(chatService.sessionPage(pageNum, pageSize, source));
	}

	@PostMapping("/submit")
	@SaCheckPermission(AiConstants.PERM_ASSISTANT_CHAT)
	public R<AiSession> submit(@RequestBody Map<String, Object> body) {
		String title = body.get("title") == null ? null : body.get("title").toString();
		String source = body.get("source") == null ? null : body.get("source").toString();
		Long bizId = body.get("bizId") == null ? null : Long.valueOf(body.get("bizId").toString());
		Long modelId = body.get("modelId") == null ? null : Long.valueOf(body.get("modelId").toString());
		return R.data(chatService.createSession(title, source, bizId, modelId));
	}

	@PostMapping("/remove")
	@SaCheckPermission(AiConstants.PERM_ASSISTANT_REMOVE)
	public R<Void> remove(@RequestParam(required = false) Long id, @RequestBody(required = false) Map<String, Object> body) {
		Long sid = id;
		if (sid == null && body != null && body.get("id") != null) {
			sid = Long.valueOf(body.get("id").toString());
		}
		chatService.removeSession(sid);
		return R.success("删除成功");
	}

	@PostMapping("/clear")
	@SaCheckPermission(AiConstants.PERM_ASSISTANT_CHAT)
	public R<Void> clear(@RequestParam(required = false) Long id, @RequestBody(required = false) Map<String, Object> body) {
		Long sid = id;
		if (sid == null && body != null && body.get("id") != null) {
			sid = Long.valueOf(body.get("id").toString());
		}
		if (sid == null) {
			return R.fail("请指定会话 id");
		}
		chatService.clearMessages(sid);
		return R.success("已清空");
	}

	@GetMapping("/export")
	@SaCheckPermission(AiConstants.PERM_ASSISTANT_EXPORT)
	public R<Map<String, Object>> export(@RequestParam Long id) {
		return R.data(chatService.exportSession(id));
	}

	@GetMapping("/messages")
	@SaCheckPermission(AiConstants.PERM_ASSISTANT_LIST)
	public R<List<AiMessage>> messages(@RequestParam Long sessionId) {
		return R.data(chatService.messages(sessionId));
	}
}
