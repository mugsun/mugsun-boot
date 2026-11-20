package com.mugsun.boot.system.service;

import com.mugsun.boot.common.constant.UserConstants;
import com.mugsun.boot.system.entity.SysUser;
import com.mybatisflex.core.query.QueryColumn;
import com.mybatisflex.core.query.QueryWrapper;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 用户下拉选项的查询条件和返回格式。用户管理的选人接口和待办的选人接口共用，两边的选项长得一样；
 * 谁能调、能看到哪些人（权限码、数据范围、是不是办理人）由各自的接口决定，不在这里。
 */
public final class UserSelectOptions {

	private UserSelectOptions() {
	}

	/**
	 * 给了 ids 就精确取这些用户（编辑回显，不限启用态和条数）；否则取启用用户，keyword 按用户名或昵称模糊匹配，
	 * 新建的排前面，最多 {@link UserConstants#USER_SELECT_LIMIT} 条。
	 */
	public static QueryWrapper query(String keyword, List<Long> ids) {
		QueryWrapper query = QueryWrapper.create();
		if (ids != null && !ids.isEmpty()) {
			query.in("id", ids);
			return query;
		}
		query.eq("status", 1);
		if (keyword != null && !keyword.isBlank()) {
			String kw = keyword.trim();
			// QueryCondition.or 自动加 Brackets 分组（同 DataScopeEngine 惯用法），生成 AND (username LIKE ? OR nickname LIKE ?)
			query.and(new QueryColumn("username").like(kw).or(new QueryColumn("nickname").like(kw)));
		}
		query.orderBy("id", false).limit(UserConstants.USER_SELECT_LIMIT);
		return query;
	}

	/** value=用户 id，label=昵称（用户名）；没填昵称（空值、空串、只有空格）的两处都用用户名 */
	public static List<Map<String, Object>> of(List<SysUser> users) {
		return users.stream()
			.map(u -> {
				Map<String, Object> option = new HashMap<>();
				option.put("value", u.getId());
				String nickname = u.getNickname() == null ? "" : u.getNickname().trim();
				option.put("label", (nickname.isEmpty() ? u.getUsername() : nickname)
					+ "（" + u.getUsername() + "）");
				return option;
			})
			.toList();
	}
}
