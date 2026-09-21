package com.mugsun.boot.app.dto;

public record AppMessageDetailVO(
	long id,
	long messageId,
	String title,
	String content,
	String time,
	boolean unread
) {
}
