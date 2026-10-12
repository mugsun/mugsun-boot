package com.mugsun.boot.system.service;

import com.mugsun.boot.system.entity.SysApiScope;
import com.mugsun.boot.system.entity.SysRole;
import com.mugsun.boot.system.entity.SysRoleApiScope;
import com.mugsun.boot.system.mapper.SysApiScopeMapper;
import com.mugsun.boot.system.mapper.SysRoleApiScopeMapper;
import com.mugsun.boot.system.mapper.SysRoleMapper;
import com.mybatisflex.core.query.QueryWrapper;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 接口权限门面：跨域装载规则与角色授权，勿直连 Mapper。
 */
@Service
public class ApiScopeService {

	private final SysApiScopeMapper apiScopeMapper;
	private final SysRoleApiScopeMapper linkMapper;
	private final SysRoleMapper roleMapper;

	public ApiScopeService(SysApiScopeMapper apiScopeMapper, SysRoleApiScopeMapper linkMapper,
						   SysRoleMapper roleMapper) {
		this.apiScopeMapper = apiScopeMapper;
		this.linkMapper = linkMapper;
		this.roleMapper = roleMapper;
	}

	/** 启用中的接口权限（status=1）。 */
	public List<SysApiScope> listEnabled() {
		return apiScopeMapper.selectListByQuery(QueryWrapper.create().eq("status", 1));
	}

	/** 角色-接口权限关联全量。 */
	public List<SysRoleApiScope> listAllRoleLinks() {
		return linkMapper.selectListByQuery(QueryWrapper.create());
	}

	/** 角色全量（用于把 roleId 换成 roleCode + tenantId）。 */
	public List<SysRole> listAllRoles() {
		return roleMapper.selectAll();
	}
}
