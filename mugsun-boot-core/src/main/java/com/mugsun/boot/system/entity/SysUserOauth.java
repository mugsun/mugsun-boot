package com.mugsun.boot.system.entity;

import com.mugsun.core.mybatis.base.BaseEntity;
import com.mybatisflex.annotation.Table;

/**
 * 用户第三方账号绑定（社交登录）
 */
@Table("sys_user_oauth")
public class SysUserOauth extends BaseEntity {

	/** 绑定的平台用户 */
	private Long userId;
	/** 来源渠道：wechat / alipay / qq 等 */
	private String source;
	/** 第三方唯一标识 */
	private String openId;
	/** 第三方 unionId（可选） */
	private String unionId;

	public Long getUserId() {
		return userId;
	}

	public void setUserId(Long userId) {
		this.userId = userId;
	}

	public String getSource() {
		return source;
	}

	public void setSource(String source) {
		this.source = source;
	}

	public String getOpenId() {
		return openId;
	}

	public void setOpenId(String openId) {
		this.openId = openId;
	}

	public String getUnionId() {
		return unionId;
	}

	public void setUnionId(String unionId) {
		this.unionId = unionId;
	}
}
