package com.mugsun.boot.app.dto;

public record AppTodoItemVO(
	long taskId,
	long instanceId,
	String title,
	String flowName,
	String nodeName,
	String createTime
) {
}
