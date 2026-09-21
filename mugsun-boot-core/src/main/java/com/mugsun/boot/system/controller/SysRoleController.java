package com.mugsun.boot.system.controller;

import cn.dev33.satoken.annotation.SaCheckLogin;
import cn.dev33.satoken.annotation.SaCheckPermission;
import com.mugsun.boot.system.entity.SysRole;
import com.mugsun.boot.system.entity.SysRoleDept;
import com.mugsun.boot.system.entity.SysRoleMenu;
import com.mugsun.boot.system.mapper.SysRoleDeptMapper;
import com.mugsun.boot.system.mapper.SysRoleMapper;
import com.mugsun.boot.system.mapper.SysRoleMenuMapper;
import com.mugsun.boot.system.payload.GrantParam;
import com.mugsun.core.tool.api.R;
import com.mybatisflex.core.paginate.Page;
import com.mybatisflex.core.query.QueryWrapper;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 角色管理
 */
@RestController
@RequestMapping("/system/role")
@SaCheckLogin
public class SysRoleController {

	private final SysRoleMapper roleMapper;
	private final SysRoleMenuMapper roleMenuMapper;
	private final SysRoleDeptMapper roleDeptMapper;

	public SysRoleController(SysRoleMapper roleMapper, SysRoleMenuMapper roleMenuMapper,
							 SysRoleDeptMapper roleDeptMapper) {
		this.roleMapper = roleMapper;
		this.roleMenuMapper = roleMenuMapper;
		this.roleDeptMapper = roleDeptMapper;
	}

	@GetMapping("/page")
	@SaCheckPermission("sys:role:list")
	public R<Page<SysRole>> page(@RequestParam(defaultValue = "1") long pageNum,
								 @RequestParam(defaultValue = "10") long pageSize,
								 @RequestParam(required = false) String roleName,
								 @RequestParam(required = false) String roleCode) {
		QueryWrapper query = QueryWrapper.create().orderBy("sort", true);
		// 查询条件（值走参数化绑定，LIKE 前后模糊）
		if (roleName != null && !roleName.isBlank()) {
			query.like("role_name", roleName.trim());
		}
		if (roleCode != null && !roleCode.isBlank()) {
			query.like("role_code", roleCode.trim());
		}
		return R.data(roleMapper.paginate(pageNum, Math.min(pageSize, 500), query));
	}

	/** 角色下拉选项（value/label 契约样例，供用户授权等场景消费） */
	@GetMapping("/select")
	public R<List<Map<String, Object>>> select() {
		List<Map<String, Object>> options = roleMapper
			.selectListByQuery(QueryWrapper.create().orderBy("sort", true))
			.stream()
			.map(role -> {
				Map<String, Object> option = new HashMap<>();
				option.put("value", role.getId());
				option.put("label", role.getRoleName());
				return option;
			})
			.toList();
		return R.data(options);
	}

	/** 角色码下拉选项（value=角色码），供流程审批人等按角色码匹配的场景消费 */
	@GetMapping("/code-select")
	@SaCheckPermission("sys:role:list")
	public R<List<Map<String, Object>>> codeSelect() {
		List<Map<String, Object>> options = roleMapper
			.selectListByQuery(QueryWrapper.create().orderBy("sort", true))
			.stream()
			.map(role -> {
				Map<String, Object> option = new HashMap<>();
				option.put("value", role.getRoleCode());
				option.put("label", role.getRoleName());
				return option;
			})
			.toList();
		return R.data(options);
	}

	@GetMapping("/detail")
	@SaCheckPermission("sys:role:list")
	public R<SysRole> detail(@RequestParam Long id) {
		return R.data(roleMapper.selectOneById(id));
	}

	@SaCheckPermission("sys:role:save")
	@PostMapping("/submit")
	@Transactional(rollbackFor = Exception.class)
	public R<Void> submit(@RequestBody SysRole role) {
		// 自定义数据权限 SQL 是原始 SQL 片段（注入面），仅平台超管可配置；非超管的输入一律忽略（新建置空/编辑保留原值）
		if (!com.mugsun.boot.tenant.TenantContext.isPlatformSuperAdmin()) {
			if (role.getId() == null) {
				role.setCustomSql(null);
			} else {
				SysRole exist = roleMapper.selectOneById(role.getId());
				role.setCustomSql(exist == null ? null : exist.getCustomSql());
			}
		}
		// 数据范围提升到「全部/自定义 SQL」与 customSql 同口径门控：仅平台超管可放宽（防自助数据权限提权）
		if (!com.mugsun.boot.tenant.TenantContext.isPlatformSuperAdmin() && role.getId() != null) {
			SysRole exist = roleMapper.selectOneById(role.getId());
			if (exist != null && !java.util.Objects.equals(exist.getDataScope(), role.getDataScope())
				&& role.getDataScope() != null
				&& (role.getDataScope() == com.mugsun.boot.common.constant.DataScopeConstants.ALL
					|| role.getDataScope() == com.mugsun.boot.common.constant.DataScopeConstants.CUSTOM_SQL)) {
				throw new com.mugsun.core.tool.exception.ServiceException("数据范围调整为全部/自定义需平台超管操作");
			}
		}
		// 内置 admin 角色码为保护码：仅平台超管可授予；存量内置角色禁止改码（通配 * 锚定该码，改码即 RBAC 停摆/提权）
		boolean isSuperAdmin = com.mugsun.boot.tenant.TenantContext.isPlatformSuperAdmin();
		if (com.mugsun.boot.common.constant.RoleConstants.ADMIN.equals(role.getRoleCode()) && !isSuperAdmin) {
			throw new com.mugsun.core.tool.exception.ServiceException("内置角色码仅平台超管可使用");
		}
		if (role.getId() == null) {
			role.sanitizeForInsert();
			role.setTenantId(null);
			roleMapper.insert(role);
		} else {
			SysRole exist = roleMapper.selectOneById(role.getId());
			if (exist == null) {
				throw new com.mugsun.core.tool.exception.ServiceException("角色不存在");
			}
			if (com.mugsun.boot.common.constant.RoleConstants.ADMIN.equals(exist.getRoleCode())
				&& !com.mugsun.boot.common.constant.RoleConstants.ADMIN.equals(role.getRoleCode())) {
				throw new com.mugsun.core.tool.exception.ServiceException("内置管理员角色禁止改码");
			}
			role.sanitizeForUpdate();
			role.setTenantId(null);
			roleMapper.update(role);
		}
		syncRoleDept(role);
		return R.success("操作成功");
	}

	/** 同步角色自定义部门：data_scope=5 时按 deptIds 重建，否则清空（避免残留旧配置穿透） */
	private void syncRoleDept(SysRole role) {
		roleDeptMapper.deleteByQuery(QueryWrapper.create().eq("role_id", role.getId()));
		if (role.getDataScope() != null && role.getDataScope() == com.mugsun.boot.common.constant.DataScopeConstants.CUSTOM_DEPT && role.getDeptIds() != null) {
			for (Long deptId : role.getDeptIds()) {
				SysRoleDept roleDept = new SysRoleDept();
				roleDept.setRoleId(role.getId());
				roleDept.setDeptId(deptId);
				roleDeptMapper.insert(roleDept);
			}
		}
	}

	/** 角色自定义部门 id 集合（data_scope=5 授权回显） */
	@GetMapping("/dept-ids")
	@SaCheckPermission("sys:role:grant")
	public R<List<Long>> deptIds(@RequestParam Long roleId) {
		assertRoleInScope(roleId);
		List<Long> ids = roleDeptMapper
			.selectListByQuery(QueryWrapper.create().eq("role_id", roleId))
			.stream()
			.map(SysRoleDept::getDeptId)
			.toList();
		return R.data(ids);
	}

	@SaCheckPermission("sys:role:remove")
	@PostMapping("/remove")
	public R<Void> remove(@RequestBody List<Long> ids) {
		// 内置 admin 角色禁删：通配 * 锚定该角色码，删除即全站 RBAC 停摆
		for (Long id : ids) {
			SysRole role = roleMapper.selectOneById(id);
			if (role != null && com.mugsun.boot.common.constant.RoleConstants.ADMIN.equals(role.getRoleCode())) {
				throw new com.mugsun.core.tool.exception.ServiceException("内置管理员角色禁止删除");
			}
		}
		roleMapper.deleteBatchByIds(ids);
		return R.success("删除成功");
	}

	/** 查询角色已授权的菜单 id 集合（授权树回显） */
	@GetMapping("/menu-ids")
	@SaCheckPermission("sys:role:grant")
	public R<List<Long>> menuIds(@RequestParam Long roleId) {
		assertRoleInScope(roleId);
		List<Long> ids = roleMenuMapper
			.selectListByQuery(QueryWrapper.create().eq("role_id", roleId))
			.stream()
			.map(SysRoleMenu::getMenuId)
			.toList();
		return R.data(ids);
	}

	/** 角色授权菜单（事务：delete+insert 原子，防部分失败剥光授权） */
	@SaCheckPermission("sys:role:grant")
	@PostMapping("/grant")
	@Transactional(rollbackFor = Exception.class)
	public R<Void> grant(@RequestBody GrantParam param) {
		assertRoleInScope(param.roleId());
		roleMenuMapper.deleteByQuery(QueryWrapper.create().eq("role_id", param.roleId()));
		if (param.menuIds() != null) {
			for (Long menuId : param.menuIds()) {
				SysRoleMenu roleMenu = new SysRoleMenu();
				roleMenu.setRoleId(param.roleId());
				roleMenu.setMenuId(menuId);
				roleMenuMapper.insert(roleMenu);
			}
		}
		return R.success("授权成功");
	}

	/** 角色归属校验：sys_role_menu/sys_role_dept 中间表无 tenant_id，必须先证角色属于当前租户（Flex 租户条件天然挡跨租户） */
	private void assertRoleInScope(Long roleId) {
		if (roleId == null || roleMapper.selectOneById(roleId) == null) {
			throw new com.mugsun.core.tool.exception.ServiceException("角色不存在");
		}
	}
}
