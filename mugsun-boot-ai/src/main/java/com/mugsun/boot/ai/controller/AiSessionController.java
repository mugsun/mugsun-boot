package com.mugsun.boot.ai.controller;

import cn.dev33.satoken.annotation.SaCheckLogin;
import cn.dev33.satoken.annotation.SaCheckPermission;
import com.mugsun.boot.ai.AiConstants;
import com.mugsun.boot.ai.entity.AiMessage;
import com.mugsun.boot.ai.entity.AiSession;
import com.mugsun.boot.ai.service.AiChatBizService;
import com.mugsun.boot.common.repeat.RepeatSubmit;
import com.mugsun.core.tool.api.R;
import com.mugsun.core.tool.exception.ServiceException;
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

	@RepeatSubmit
	@PostMapping("/submit")
	@SaCheckPermission(AiConstants.PERM_ASSISTANT_CHAT)
	public R<AiSession> submit(@RequestBody(required = false) Map<String, Object> body) {
		if (body == null) {
			throw new ServiceException("请填写会话标题。用来在列表里区分");
		}
		boolean update = body.get("id") != null && !String.valueOf(body.get("id")).isBlank();
		AiSession saved;
		if (update) {
			boolean modelPresent = body.containsKey("modelId");
			saved = chatService.updateSession(parseId(body.get("id"), "会话编号请填写数字，可以从列表里复制"),
				body.get("title") == null ? null : body.get("title").toString(),
				optionalId(body.get("modelId"), "模型编号请填写数字，可以从列表里复制"),
				modelPresent);
		} else {
			saved = chatService.createSession(
				body.get("title") == null ? null : body.get("title").toString(),
				body.get("source") == null ? null : body.get("source").toString(),
				optionalId(body.get("bizId"), "业务编号请填写数字"),
				optionalId(body.get("modelId"), "模型编号请填写数字，可以从列表里复制"));
		}
		R<AiSession> result = R.data(saved);
		result.setMsg(update
			? "已保存。这条会话会按最新设置继续"
			: "已新建。可以在这里开始提问");
		return result;
	}

	@RepeatSubmit
	@PostMapping("/remove")
	@SaCheckPermission(AiConstants.PERM_ASSISTANT_REMOVE)
	public R<Void> remove(@RequestParam(required = false) String id,
						  @RequestBody(required = false) Map<String, Object> body) {
		Object raw = id;
		if ((raw == null || String.valueOf(raw).isBlank()) && body != null) {
			raw = body.get("id");
		}
		if (raw == null || String.valueOf(raw).isBlank()) {
			throw new ServiceException("请先选择要删除的会话");
		}
		AiSession removed = chatService.removeSession(parseId(raw, "会话编号请填写数字，可以从列表里复制"));
		String title = removed.getTitle() == null || removed.getTitle().isBlank() ? "这条会话" : removed.getTitle();
		return R.success("已删除「" + title + "」。列表里不会再出现");
	}

	@RepeatSubmit
	@PostMapping("/clear")
	@SaCheckPermission(AiConstants.PERM_ASSISTANT_CHAT)
	public R<Void> clear(@RequestParam(required = false) String id,
						 @RequestBody(required = false) Map<String, Object> body) {
		Object raw = id;
		if ((raw == null || String.valueOf(raw).isBlank()) && body != null) {
			raw = body.get("id");
		}
		if (raw == null || String.valueOf(raw).isBlank()) {
			throw new ServiceException("请先选择要清空的会话");
		}
		chatService.clearMessages(parseId(raw, "会话编号请填写数字，可以从列表里复制"));
		return R.success("已清空。这条会话里的消息不会再出现");
	}

	@GetMapping("/export")
	@SaCheckPermission(AiConstants.PERM_ASSISTANT_EXPORT)
	public R<Map<String, Object>> export(@RequestParam(required = false) String id) {
		if (id == null || id.isBlank()) {
			throw new ServiceException("请先选择要导出的会话");
		}
		return R.data(chatService.exportSession(parseId(id, "会话编号请填写数字，可以从列表里复制")));
	}

	@GetMapping("/messages")
	@SaCheckPermission(AiConstants.PERM_ASSISTANT_LIST)
	public R<List<AiMessage>> messages(@RequestParam(required = false) String sessionId) {
		if (sessionId == null || sessionId.isBlank()) {
			throw new ServiceException("请先选择要查看的会话");
		}
		return R.data(chatService.messages(parseId(sessionId, "会话编号请填写数字，可以从列表里复制")));
	}

	private static Long optionalId(Object raw, String message) {
		if (raw == null || String.valueOf(raw).isBlank() || "null".equals(String.valueOf(raw))) {
			return null;
		}
		return parseId(raw, message);
	}

	private static Long parseId(Object raw, String message) {
		String text = String.valueOf(raw).trim();
		if (!text.matches("\\d{1,19}")) {
			throw new ServiceException(message);
		}
		return Long.valueOf(text);
	}
}
