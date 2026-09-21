package com.mugsun.boot.app.dto;

import java.util.List;

public record AppHomeVO(
	AppUserVO user,
	long todoCount,
	long messageUnread,
	long noticeUnread,
	List<AppTodoItemVO> todos,
	List<AppNoticeItemVO> notices
) {
}
