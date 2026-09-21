package com.mugsun.boot.app.dto;

import java.util.List;

public record AppTodoDetailVO(
	long taskId,
	long instanceId,
	String flowName,
	String nodeName,
	String createTime,
	List<AppFieldVO> fields
) {
}
