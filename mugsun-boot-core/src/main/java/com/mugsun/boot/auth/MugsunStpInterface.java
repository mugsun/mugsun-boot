package com.mugsun.boot.auth;

import cn.dev33.satoken.stp.StpInterface;
import com.mugsun.boot.common.constant.RoleConstants;
import com.mugsun.boot.system.entity.SysMenu;
import com.mugsun.boot.system.entity.SysRole;
import com.mugsun.boot.system.entity.SysRoleMenu;
import com.mugsun.boot.system.entity.SysUserRole;
import com.mugsun.boot.system.mapper.SysMenuMapper;
import com.mugsun.boot.system.mapper.SysRoleMapper;
import com.mugsun.boot.system.mapper.SysRoleMenuMapper;
import com.mugsun.boot.system.mapper.SysUserRoleMapper;
import com.mugsun.boot.tenant.TenantContext;
import com.mybatisflex.core.query.QueryWrapper;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Sa-Token 权限数据源：用户 → 角色 → 菜单权限码。
 * 权限/角色按全局 user_id 解析，**忽略租户上下文**——不受超管切换/异步透传/开放接口令牌租户影响，
 * 保证鉴权始终基于用户真实身份（否则切换租户即丢失本人权限致越权 403）。
 */
@Component
public class MugsunStpInterface implements StpInterface {

	private final SysUserRoleMapper userRoleMapper;
	private final SysRoleMapper roleMapper;
	private final SysRoleMenuMapper roleMenuMapper;
	private final SysMenuMapper menuMapper;

	public MugsunStpInterface(SysUserRoleMapper userRoleMapper, SysRoleMapper roleMapper,
							  SysRoleMenuMapper roleMenuMapper, SysMenuMapper menuMapper) {
		this.userRoleMapper = userRoleMapper;
		this.roleMapper = roleMapper;
		this.roleMenuMapper = roleMenuMapper;
		this.menuMapper = menuMapper;
	}

	@Override
	public List<String> getPermissionList(Object loginId, String loginType) {
		return TenantContext.ignore(() -> {
			List<Long> roleIds = roleIdsOf(loginId);
			if (roleIds.isEmpty()) {
				return Collections.emptyList();
			}
			List<SysRole> roles = roleMapper.selectListByIds(roleIds);
			// 超级管理员通配全部权限
			if (roles.stream().anyMatch(r -> RoleConstants.ADMIN.equals(r.getRoleCode()))) {
				return List.of("*");
			}
			List<Long> menuIds = roleMenuMapper.selectListByQuery(QueryWrapper.create().in("role_id", roleIds))
				.stream().map(SysRoleMenu::getMenuId).collect(Collectors.toList());
			if (menuIds.isEmpty()) {
				return Collections.emptyList();
			}
			return menuMapper.selectListByIds(menuIds).stream()
				.map(SysMenu::getPermission)
				.filter(p -> p != null && !p.isBlank())
				.collect(Collectors.toList());
		});
	}

	@Override
	public List<String> getRoleList(Object loginId, String loginType) {
		return TenantContext.ignore(() -> {
			List<Long> roleIds = roleIdsOf(loginId);
			if (roleIds.isEmpty()) {
				return Collections.emptyList();
			}
			return roleMapper.selectListByIds(roleIds).stream()
				.map(SysRole::getRoleCode)
				.collect(Collectors.toList());
		});
	}

	private List<Long> roleIdsOf(Object loginId) {
		Long userId = Long.valueOf(loginId.toString());
		return userRoleMapper.selectListByQuery(QueryWrapper.create().eq("user_id", userId))
			.stream().map(SysUserRole::getRoleId).collect(Collectors.toList());
	}
}
