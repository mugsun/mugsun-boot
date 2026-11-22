package com.mugsun.boot.app;

import cn.dev33.satoken.stp.StpUtil;
import com.mugsun.boot.app.dto.AppHomeVO;
import com.mugsun.boot.app.dto.AppNoticeSummaryVO;
import com.mugsun.boot.app.dto.AppTodoItemVO;
import com.mugsun.boot.app.dto.AppUserVO;
import com.mugsun.boot.system.service.NoticeViewer;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class AppHomeService {

	private final AppUserReader userReader;
	private final AppTodoReader todoReader;
	private final AppInboxService inboxService;
	private final AppNoticeReader noticeReader;

	public AppHomeService(AppUserReader userReader, AppTodoReader todoReader, AppInboxService inboxService,
						  AppNoticeReader noticeReader) {
		this.userReader = userReader;
		this.todoReader = todoReader;
		this.inboxService = inboxService;
		this.noticeReader = noticeReader;
	}

	public AppHomeVO home() {
		AppUserVO me = userReader.me();
		List<AppTodoItemVO> todos = todoReader.listOf(StpUtil.getLoginIdAsLong());
		int todoLimit = Math.min(todos.size(), AppConstants.HOME_TODO_LIMIT);
		AppNoticeSummaryVO notices = noticeReader.summaryOf(NoticeViewer.ofLogin(), AppConstants.HOME_NOTICE_LIMIT);
		return new AppHomeVO(
			me,
			todos.size(),
			inboxService.messageUnread(),
			notices.unread(),
			todos.subList(0, todoLimit),
			notices.recent()
		);
	}
}
