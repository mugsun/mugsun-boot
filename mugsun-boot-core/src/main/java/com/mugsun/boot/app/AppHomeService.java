package com.mugsun.boot.app;

import cn.dev33.satoken.stp.StpUtil;
import com.mugsun.boot.app.dto.AppHomeVO;
import com.mugsun.boot.app.dto.AppTodoItemVO;
import com.mugsun.boot.app.dto.AppUserVO;
import com.mugsun.boot.system.entity.SysUser;
import com.mugsun.boot.system.mapper.SysUserMapper;
import com.mugsun.boot.tenant.TenantContext;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class AppHomeService {

	private final SysUserMapper userMapper;
	private final AppTodoService todoService;
	private final AppInboxService inboxService;

	public AppHomeService(SysUserMapper userMapper, AppTodoService todoService, AppInboxService inboxService) {
		this.userMapper = userMapper;
		this.todoService = todoService;
		this.inboxService = inboxService;
	}

	public AppHomeVO home() {
		SysUser user = TenantContext.ignore(() -> userMapper.selectOneById(StpUtil.getLoginIdAsLong()));
		String nick = user.getNickname() == null || user.getNickname().isBlank()
			? user.getUsername() : user.getNickname();
		List<AppTodoItemVO> todos = todoService.list();
		int todoLimit = Math.min(todos.size(), AppConstants.HOME_TODO_LIMIT);
		return new AppHomeVO(
			new AppUserVO(user.getId(), user.getUsername(), nick),
			todos.size(),
			inboxService.messageUnread(),
			inboxService.noticeUnread(),
			todos.subList(0, todoLimit),
			inboxService.recentNotices(AppConstants.HOME_NOTICE_LIMIT)
		);
	}

	public AppUserVO me() {
		SysUser user = TenantContext.ignore(() -> userMapper.selectOneById(StpUtil.getLoginIdAsLong()));
		String nick = user.getNickname() == null || user.getNickname().isBlank()
			? user.getUsername() : user.getNickname();
		return new AppUserVO(user.getId(), user.getUsername(), nick);
	}
}
