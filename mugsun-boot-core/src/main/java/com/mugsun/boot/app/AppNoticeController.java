package com.mugsun.boot.app;

import cn.dev33.satoken.annotation.SaCheckLogin;
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

/**
 * 手机端消息中心里的公告。地址和原来一样，还在 /app/inbox 下面；站内信在 {@link AppInboxController}。
 *
 * <p>单独一个类，是因为公告和站内信不是一个模块，微服务版里它们不在同一个服务，统一入口按地址前缀分发。
 */
@RestController
@RequestMapping("/app/inbox/notices")
@SaCheckLogin
public class AppNoticeController {

	private final AppNoticeService noticeService;

	public AppNoticeController(AppNoticeService noticeService) {
		this.noticeService = noticeService;
	}

	@GetMapping
	public R<AppPageVO<AppNoticeItemVO>> notices(@RequestParam(defaultValue = "1") long pageNum,
												   @RequestParam(defaultValue = "20") long pageSize) {
		return R.data(noticeService.notices(pageNum, pageSize));
	}

	@GetMapping("/{id}")
	public R<AppNoticeDetailVO> notice(@PathVariable Long id) {
		return R.data(noticeService.notice(id));
	}

	@PostMapping("/{id}/read")
	public R<Void> readNotice(@PathVariable Long id) {
		noticeService.readNotice(id);
		return R.success("已读");
	}
}
