package com.mugsun.boot.app.dto;

public record AppNoticeItemVO(
	long id,
	String title,
	String time,
	boolean unread
) {
}
