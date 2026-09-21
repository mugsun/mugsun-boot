package com.mugsun.boot.security.entity;

import com.mybatisflex.annotation.Id;
import com.mybatisflex.annotation.KeyType;
import com.mybatisflex.annotation.Table;
import com.mybatisflex.core.keygen.KeyGenerators;

import java.time.LocalDateTime;

/**
 * 历史密码（防重复使用），仅追加不删除。
 */
@Table("sys_password_log")
public class SysPasswordLog {

	@Id(keyType = KeyType.Generator, value = KeyGenerators.flexId)
	private Long id;
	private Long userId;
	private String password;
	private LocalDateTime createTime;

	public Long getId() {
		return id;
	}

	public void setId(Long id) {
		this.id = id;
	}

	public Long getUserId() {
		return userId;
	}

	public void setUserId(Long userId) {
		this.userId = userId;
	}

	public String getPassword() {
		return password;
	}

	public void setPassword(String password) {
		this.password = password;
	}

	public LocalDateTime getCreateTime() {
		return createTime;
	}

	public void setCreateTime(LocalDateTime createTime) {
		this.createTime = createTime;
	}
}
