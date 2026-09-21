package com.mugsun.boot.system.entity;

import com.mugsun.core.mybatis.base.BaseEntity;
import com.mybatisflex.annotation.Column;
import com.mybatisflex.annotation.Table;

import java.time.LocalDateTime;

/**
 * 通知阅读记录：每用户一行，read_count 累加；查询时装配用户昵称/部门（瞬态）。
 */
@Table("sys_notice_read")
public class SysNoticeRead extends BaseEntity {

	private Long noticeId;
	private Long userId;
	private Integer readCount;
	private LocalDateTime firstTime;
	private LocalDateTime lastTime;

	/** 阅读人昵称（关联装配，非库字段） */
	@Column(ignore = true)
	private String nickname;
	/** 阅读人部门名（关联装配，非库字段） */
	@Column(ignore = true)
	private String deptName;

	public Long getNoticeId() {
		return noticeId;
	}

	public void setNoticeId(Long noticeId) {
		this.noticeId = noticeId;
	}

	public Long getUserId() {
		return userId;
	}

	public void setUserId(Long userId) {
		this.userId = userId;
	}

	public Integer getReadCount() {
		return readCount;
	}

	public void setReadCount(Integer readCount) {
		this.readCount = readCount;
	}

	public LocalDateTime getFirstTime() {
		return firstTime;
	}

	public void setFirstTime(LocalDateTime firstTime) {
		this.firstTime = firstTime;
	}

	public LocalDateTime getLastTime() {
		return lastTime;
	}

	public void setLastTime(LocalDateTime lastTime) {
		this.lastTime = lastTime;
	}

	public String getNickname() {
		return nickname;
	}

	public void setNickname(String nickname) {
		this.nickname = nickname;
	}

	public String getDeptName() {
		return deptName;
	}

	public void setDeptName(String deptName) {
		this.deptName = deptName;
	}
}
