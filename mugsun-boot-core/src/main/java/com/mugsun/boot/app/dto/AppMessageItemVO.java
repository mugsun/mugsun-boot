package com.mugsun.boot.app.dto;

public record AppMessageItemVO(
	long id,
	long messageId,
	String title,
	String time,
	boolean unread
) {
}
