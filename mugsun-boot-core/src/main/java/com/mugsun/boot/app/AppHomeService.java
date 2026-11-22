package com.mugsun.boot.app;

import cn.dev33.satoken.stp.StpUtil;
import com.mugsun.boot.app.dto.AppHomeVO;
import com.mugsun.boot.app.dto.AppNoticeSummaryVO;
import com.mugsun.boot.app.dto.AppTodoItemVO;
import com.mugsun.boot.app.dto.AppUserVO;
import com.mugsun.boot.system.entity.SysUser;
import com.mugsun.boot.system.mapper.SysUserMapper;
import com.mugsun.boot.system.service.NoticeViewer;
import com.mugsun.boot.tenant.TenantContext;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class AppHomeService {

	private final SysUserMapper userMapper;
	private final AppTodoReader todoReader;
	private final AppInboxService inboxService;
	private final AppNoticeReader noticeReader;

	public AppHomeService(SysUserMapper userMapper, AppTodoReader todoReader, AppInboxService inboxService,
						  AppNoticeReader noticeReader) {
		this.userMapper = userMapper;
		this.todoReader = todoReader;
		this.inboxService = inboxService;
		this.noticeReader = noticeReader;
	}

	public AppHomeVO home() {
		SysUser user = TenantContext.ignore(() -> userMapper.selectOneById(StpUtil.getLoginIdAsLong()));
		String nick = user.getNickname() == null || user.getNickname().isBlank()
			? user.getUsername() : user.getNickname();
		List<AppTodoItemVO> todos = todoReader.listOf(StpUtil.getLoginIdAsLong());
		int todoLimit = Math.min(todos.size(), AppConstants.HOME_TODO_LIMIT);
		AppNoticeSummaryVO notices = noticeReader.summaryOf(NoticeViewer.ofLogin(), AppConstants.HOME_NOTICE_LIMIT);
		return new AppHomeVO(
			new AppUserVO(user.getId(), user.getUsername(), nick),
			todos.size(),
			inboxService.messageUnread(),
			notices.unread(),
			todos.subList(0, todoLimit),
			notices.recent()
		);
	}

	public AppUserVO me() {
		SysUser user = TenantContext.ignore(() -> userMapper.selectOneById(StpUtil.getLoginIdAsLong()));
		String nick = user.getNickname() == null || user.getNickname().isBlank()
			? user.getUsername() : user.getNickname();
		return new AppUserVO(user.getId(), user.getUsername(), nick);
	}
}
