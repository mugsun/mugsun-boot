package com.mugsun.boot.app.dto;

public record AppLoginDTO(
	String username,
	String password,
	String ticket,
	String tenantId
) {
}
