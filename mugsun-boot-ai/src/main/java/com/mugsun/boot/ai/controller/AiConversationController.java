package com.mugsun.boot.ai.controller;

import cn.dev33.satoken.annotation.SaCheckLogin;
import cn.dev33.satoken.annotation.SaCheckPermission;
import com.mugsun.boot.ai.AiConstants;
import com.mugsun.boot.ai.entity.AiMessage;
import com.mugsun.boot.ai.entity.AiSession;
import com.mugsun.boot.ai.service.AiChatBizService;
import com.mugsun.boot.ai.support.AiCsv;
import com.mugsun.core.tool.api.R;
import com.mugsun.core.tool.exception.ServiceException;
import com.mybatisflex.core.paginate.Page;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.bind.annotation.*;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

@RestController
@RequestMapping("/system/ai/conversation")
@SaCheckLogin
public class AiConversationController {

	private final AiChatBizService chatService;

	public AiConversationController(AiChatBizService chatService) {
		this.chatService = chatService;
	}

	@GetMapping("/page")
	@SaCheckPermission(AiConstants.PERM_CONVERSATION_LIST)
	public R<Page<AiSession>> page(@RequestParam(defaultValue = "1") long pageNum,
								   @RequestParam(defaultValue = "20") long pageSize) {
		return R.data(chatService.conversationPage(pageNum, pageSize));
	}

	@GetMapping("/detail")
	@SaCheckPermission(AiConstants.PERM_CONVERSATION_DETAIL)
	public R<List<AiMessage>> detail(@RequestParam(required = false) Long sessionId,
									 @RequestParam(required = false) Long id) {
		Long real = sessionId != null ? sessionId : id;
		if (real == null) {
			throw new ServiceException("缺少会话 id");
		}
		return R.data(chatService.messages(real));
	}

	@GetMapping("/export")
	@SaCheckPermission(AiConstants.PERM_CONVERSATION_EXPORT)
	public void export(@RequestParam(required = false) String keyword, HttpServletResponse response) throws IOException {
		Page<AiSession> page = chatService.conversationPage(1, 5000);
		List<String> rows = new ArrayList<>();
		String key = keyword == null ? "" : keyword.trim();
		for (AiSession row : page.getRecords()) {
			String title = row.getTitle() == null ? "" : row.getTitle();
			if (!key.isEmpty() && !title.contains(key)) {
				continue;
			}
			rows.add(String.join(",",
				AiCsv.cell(row.getId()),
				AiCsv.cell(title),
				AiCsv.cell(row.getSource()),
				AiCsv.cell(row.getTotalTokens()),
				AiCsv.cell(row.getMessageCount()),
				AiCsv.cell(row.getLastTime())));
		}
		AiCsv.write(response, "conversations.csv", "id,title,source,tokens,messages,lastTime", rows);
	}
}
