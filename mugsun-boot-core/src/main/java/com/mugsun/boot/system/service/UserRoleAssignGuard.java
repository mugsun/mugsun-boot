package com.mugsun.boot.system.service;

import cn.dev33.satoken.stp.StpUtil;
import com.mugsun.boot.common.constant.DataScopeConstants;
import com.mugsun.boot.common.constant.RoleConstants;
import com.mugsun.boot.system.entity.SysRole;
import com.mugsun.boot.system.entity.SysRoleMenu;
import com.mugsun.boot.system.entity.SysUserRole;
import com.mugsun.boot.system.mapper.SysRoleMapper;
import com.mugsun.boot.system.mapper.SysRoleMenuMapper;
import com.mugsun.boot.system.mapper.SysUserRoleMapper;
import com.mugsun.boot.tenant.TenantContext;
import com.mugsun.core.tool.exception.ServiceException;
import com.mybatisflex.core.query.QueryWrapper;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 给用户挂角色的统一校验：用户保存（新建/编辑）和「分配角色」都先过这里。
 * <p>平台超管、内置管理员角色持有者不受限。其他人的用户管理权是委派来的，不能借它给人（包括自己新建的账号）
 * 挂上比自己大的角色：不能改自己的角色，不能动管理员账号的角色；增删的角色不能是内置管理员角色，
 * 菜单必须在自己的菜单之内，数据范围为全部或自定义语句时自己须有全部数据范围（与角色保存只许平台超管设这两档同口径）。
 */
@Component
public class UserRoleAssignGuard {

	private final SysRoleMapper roleMapper;
	private final SysUserRoleMapper userRoleMapper;
	private final SysRoleMenuMapper roleMenuMapper;

	public UserRoleAssignGuard(SysRoleMapper roleMapper, SysUserRoleMapper userRoleMapper,
							   SysRoleMenuMapper roleMenuMapper) {
		this.roleMapper = roleMapper;
		this.userRoleMapper = userRoleMapper;
		this.roleMenuMapper = roleMenuMapper;
	}

	/** 当前操作者可分配任意角色（平台超管或内置管理员角色持有者） */
	public static boolean unrestricted() {
		return TenantContext.isPlatformSuperAdmin() || StpUtil.getRoleList().contains(RoleConstants.ADMIN);
	}

	/**
	 * @param userId  目标用户；新建时传 null
	 * @param roleIds 保存后的完整角色列表；null 表示这次不改角色
	 */
	public void assertAssignable(Long userId, Collection<Long> roleIds) {
		if (roleIds == null || unrestricted()) {
			return;
		}
		Set<Long> current = userId == null ? Set.of() : roleIdsOf(userId);
		Set<Long> requested = roleIds.stream().filter(Objects::nonNull).collect(Collectors.toSet());
		Set<Long> changed = new LinkedHashSet<>(requested);
		changed.removeAll(current);
		current.stream().filter(id -> !requested.contains(id)).forEach(changed::add);
		if (changed.isEmpty()) {
			return;
		}
		long operatorId = StpUtil.getLoginIdAsLong();
		if (userId != null && userId == operatorId) {
			throw new ServiceException("不能修改自己的角色。需要调整请找管理员");
		}
		if (rolesOf(current).stream().anyMatch(UserRoleAssignGuard::isAdmin)) {
			throw new ServiceException("这是管理员账号，只有管理员能调整它的角色");
		}
		List<SysRole> roles = rolesOf(changed);
		if (roles.stream().anyMatch(UserRoleAssignGuard::isAdmin)) {
			throw new ServiceException("内置管理员角色仅管理员可授予");
		}
		Set<Long> operatorRoleIds = roleIdsOf(operatorId);
		Set<Long> operatorMenus = menuIdsOf(operatorRoleIds);
		boolean operatorSeesAll = rolesOf(operatorRoleIds).stream()
			.anyMatch(r -> Objects.equals(r.getDataScope(), DataScopeConstants.ALL));
		for (SysRole role : roles) {
			if (!operatorMenus.containsAll(menuIdsOf(Set.of(role.getId())))) {
				throw new ServiceException("角色「" + role.getRoleName() + "」的菜单权限超出了你自己的权限，不能分配或移除");
			}
			if (!operatorSeesAll && (Objects.equals(role.getDataScope(), DataScopeConstants.ALL)
				|| Objects.equals(role.getDataScope(), DataScopeConstants.CUSTOM_SQL))) {
				throw new ServiceException("角色「" + role.getRoleName() + "」的数据范围超出了你自己的范围，不能分配或移除");
			}
		}
	}

	private static boolean isAdmin(SysRole role) {
		return RoleConstants.ADMIN.equals(role.getRoleCode());
	}

	private Set<Long> roleIdsOf(Long userId) {
		return userRoleMapper.selectListByQuery(QueryWrapper.create().eq("user_id", userId))
			.stream().map(SysUserRole::getRoleId).collect(Collectors.toSet());
	}

	// 空集合必须提前返回：in 条件遇到空集合会被忽略，查出的是全表
	private List<SysRole> rolesOf(Set<Long> roleIds) {
		return roleIds.isEmpty() ? List.of() : roleMapper.selectListByIds(roleIds);
	}

	private Set<Long> menuIdsOf(Set<Long> roleIds) {
		if (roleIds.isEmpty()) {
			return Set.of();
		}
		return roleMenuMapper.selectListByQuery(QueryWrapper.create().in("role_id", roleIds))
			.stream().map(SysRoleMenu::getMenuId).collect(Collectors.toSet());
	}
}
