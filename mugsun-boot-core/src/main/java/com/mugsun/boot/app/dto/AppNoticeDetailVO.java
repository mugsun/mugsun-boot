package com.mugsun.boot.app.dto;

public record AppNoticeDetailVO(
	long id,
	String title,
	String content,
	String time,
	boolean unread
) {
}
