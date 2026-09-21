package com.mugsun.boot.app;

import cn.dev33.satoken.annotation.SaCheckLogin;
import com.mugsun.boot.app.dto.AppMessageDetailVO;
import com.mugsun.boot.app.dto.AppMessageItemVO;
import com.mugsun.boot.app.dto.AppNoticeDetailVO;
import com.mugsun.boot.app.dto.AppNoticeItemVO;
import com.mugsun.boot.app.dto.AppPageVO;
import com.mugsun.core.tool.api.R;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/app/inbox")
@SaCheckLogin
public class AppInboxController {

	private final AppInboxService inboxService;

	public AppInboxController(AppInboxService inboxService) {
		this.inboxService = inboxService;
	}

	@GetMapping("/messages")
	public R<AppPageVO<AppMessageItemVO>> messages(@RequestParam(defaultValue = "1") long pageNum,
													 @RequestParam(defaultValue = "20") long pageSize) {
		return R.data(inboxService.messages(pageNum, pageSize));
	}

	@GetMapping("/messages/{id}")
	public R<AppMessageDetailVO> message(@PathVariable Long id) {
		return R.data(inboxService.message(id));
	}

	@PostMapping("/messages/{messageId}/read")
	public R<Void> readMessage(@PathVariable Long messageId) {
		inboxService.readMessage(messageId);
		return R.success("已读");
	}

	@GetMapping("/notices")
	public R<AppPageVO<AppNoticeItemVO>> notices(@RequestParam(defaultValue = "1") long pageNum,
												   @RequestParam(defaultValue = "20") long pageSize) {
		return R.data(inboxService.notices(pageNum, pageSize));
	}

	@GetMapping("/notices/{id}")
	public R<AppNoticeDetailVO> notice(@PathVariable Long id) {
		return R.data(inboxService.notice(id));
	}

	@PostMapping("/notices/{id}/read")
	public R<Void> readNotice(@PathVariable Long id) {
		inboxService.readNotice(id);
		return R.success("已读");
	}
}
