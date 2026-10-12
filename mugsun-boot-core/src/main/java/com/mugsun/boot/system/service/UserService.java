package com.mugsun.boot.system.service;

import com.mugsun.boot.system.mapper.SysUserMapper;
import com.mybatisflex.core.query.QueryWrapper;
import org.springframework.stereotype.Service;

import java.util.Collection;
import java.util.HashMap;
import java.util.Map;

/**
 * 用户域只读门面：跨域调用方取展示名等，勿直连 {@link SysUserMapper}。
 */
@Service
public class UserService {

	private final SysUserMapper userMapper;

	public UserService(SysUserMapper userMapper) {
		this.userMapper = userMapper;
	}

	/** 按 id 批量取展示名（昵称优先，否则账号）。缺行不入表。 */
	public Map<Long, String> displayNamesByIds(Collection<Long> ids) {
		Map<Long, String> names = new HashMap<>();
		if (ids == null || ids.isEmpty()) {
			return names;
		}
		userMapper.selectListByQuery(QueryWrapper.create().in("id", ids)).forEach(u -> names.put(
			u.getId(),
			u.getNickname() == null || u.getNickname().isBlank() ? u.getUsername() : u.getNickname()));
		return names;
	}
}
