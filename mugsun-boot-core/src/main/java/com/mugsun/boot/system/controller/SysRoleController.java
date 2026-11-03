package com.mugsun.boot.system.controller;

import cn.dev33.satoken.annotation.SaCheckLogin;
import cn.dev33.satoken.annotation.SaCheckPermission;
import cn.dev33.satoken.stp.StpUtil;
import com.mugsun.boot.common.constant.DataScopeConstants;
import com.mugsun.boot.common.constant.RoleConstants;
import com.mugsun.boot.common.constant.TenantConstants;
import com.mugsun.boot.common.module.ModuleMenuFilter;
import com.mugsun.boot.common.repeat.RepeatSubmit;
import com.mugsun.boot.system.entity.SysMenu;
import com.mugsun.boot.system.entity.SysRole;
import com.mugsun.boot.system.entity.SysRoleDept;
import com.mugsun.boot.system.entity.SysRoleMenu;
import com.mugsun.boot.system.entity.SysTenant;
import com.mugsun.boot.system.entity.SysUserRole;
import com.mugsun.boot.system.mapper.SysMenuMapper;
import com.mugsun.boot.system.mapper.SysRoleDeptMapper;
import com.mugsun.boot.system.mapper.SysRoleMapper;
import com.mugsun.boot.system.mapper.SysRoleMenuMapper;
import com.mugsun.boot.system.mapper.SysUserRoleMapper;
import com.mugsun.boot.system.payload.GrantParam;
import com.mugsun.boot.tenant.TenantContext;
import com.mugsun.boot.tenant.TenantPackageModules;
import com.mugsun.boot.tenant.TenantValidator;
import com.mugsun.core.tool.api.R;
import com.mugsun.core.tool.exception.ServiceException;
import com.mybatisflex.core.paginate.Page;
import com.mybatisflex.core.query.QueryWrapper;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 角色管理
 */
@RestController
@RequestMapping("/system/role")
@SaCheckLogin
public class SysRoleController {

	private static final Pattern CODE = Pattern.compile("^[A-Za-z][A-Za-z0-9_-]{0,63}$");

	private final SysRoleMapper roleMapper;
	private final SysRoleMenuMapper roleMenuMapper;
	private final SysRoleDeptMapper roleDeptMapper;
	private final SysUserRoleMapper userRoleMapper;
	private final SysMenuMapper menuMapper;
	private final ModuleMenuFilter moduleMenuFilter;
	private final TenantValidator tenantValidator;

	public SysRoleController(SysRoleMapper roleMapper, SysRoleMenuMapper roleMenuMapper,
							 SysRoleDeptMapper roleDeptMapper, SysUserRoleMapper userRoleMapper,
							 SysMenuMapper menuMapper, ModuleMenuFilter moduleMenuFilter,
							 TenantValidator tenantValidator) {
		this.roleMapper = roleMapper;
		this.roleMenuMapper = roleMenuMapper;
		this.roleDeptMapper = roleDeptMapper;
		this.userRoleMapper = userRoleMapper;
		this.menuMapper = menuMapper;
		this.moduleMenuFilter = moduleMenuFilter;
		this.tenantValidator = tenantValidator;
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
		SysRole role = roleMapper.selectOneById(id);
		if (role == null) {
			throw new ServiceException("这个角色不存在，或属于其他租户。请刷新列表后再看");
		}
		return R.data(role);
	}

	@SaCheckPermission("sys:role:save")
	@RepeatSubmit
	@PostMapping("/submit")
	@Transactional(rollbackFor = Exception.class)
	public R<Void> submit(@RequestBody SysRole role) {
		if (role == null) {
			throw new ServiceException("请填写角色内容后再保存");
		}
		String name = role.getRoleName() == null ? "" : role.getRoleName().trim();
		if (name.isEmpty()) {
			throw new ServiceException("请填写角色名称");
		}
		if (name.length() > 64) {
			throw new ServiceException("角色名称请控制在 64 字以内");
		}
		role.setRoleName(name);
		String code = role.getRoleCode() == null ? "" : role.getRoleCode().trim();
		if (code.isEmpty()) {
			throw new ServiceException("请填写角色编码");
		}
		if (!CODE.matcher(code).matches()) {
			throw new ServiceException("角色编码请用字母开头，只能包含字母、数字、下划线和中横线");
		}
		role.setRoleCode(code);
		if (role.getSort() == null) {
			role.setSort(0);
		}
		if (role.getSort() < 0 || role.getSort() > 9999) {
			throw new ServiceException("排序请填 0 到 9999 的整数");
		}
		if (role.getDataScope() == null || role.getDataScope() < DataScopeConstants.ALL
			|| role.getDataScope() > DataScopeConstants.CUSTOM_SQL) {
			throw new ServiceException("请选择数据范围：全部、本部门、本部门及子部门、仅本人、自定义部门或自定义语句");
		}
		if (role.getDataScope() == DataScopeConstants.CUSTOM_DEPT
			&& (role.getDeptIds() == null || role.getDeptIds().isEmpty())) {
			throw new ServiceException("选择自定义部门时，请至少勾选一个部门");
		}
		boolean isSuperAdmin = TenantContext.isPlatformSuperAdmin();
		// 自定义数据权限 SQL 是原始 SQL 片段，仅平台超管可配置；其他人新建时清空，编辑时保留原值
		if (!isSuperAdmin) {
			if (role.getId() == null) {
				role.setCustomSql(null);
			} else {
				SysRole exist = roleMapper.selectOneById(role.getId());
				role.setCustomSql(exist == null ? null : exist.getCustomSql());
			}
		}
		if (!isSuperAdmin && (role.getDataScope() == DataScopeConstants.ALL
			|| role.getDataScope() == DataScopeConstants.CUSTOM_SQL)) {
			if (role.getId() == null) {
				throw new ServiceException("数据范围选全部或自定义语句时，需要平台超管来设置。请改成本部门、仅本人或自定义部门");
			}
			SysRole exist = roleMapper.selectOneById(role.getId());
			if (exist != null && !Objects.equals(exist.getDataScope(), role.getDataScope())) {
				throw new ServiceException("数据范围改成全部或自定义语句时，需要平台超管来设置。请改回原来的范围，或选本部门、仅本人、自定义部门");
			}
		}
		if (RoleConstants.ADMIN.equals(role.getRoleCode()) && !isSuperAdmin) {
			throw new ServiceException("内置管理员编码只有平台超管能使用。请换一个编码");
		}
		QueryWrapper dup = QueryWrapper.create().eq("role_code", code);
		if (role.getId() != null) {
			dup.ne("id", role.getId());
		}
		if (roleMapper.selectCountByQuery(dup) > 0) {
			throw new ServiceException("角色编码已存在。请换一个编码，或在列表里编辑原来那个角色");
		}
		if (role.getId() == null) {
			role.sanitizeForInsert();
			role.setTenantId(TenantContext.current());
			roleMapper.insert(role);
		} else {
			SysRole exist = roleMapper.selectOneById(role.getId());
			if (exist == null) {
				throw new ServiceException("这个角色不存在，或属于其他租户。请刷新列表后再改");
			}
			if (RoleConstants.ADMIN.equals(exist.getRoleCode()) && !RoleConstants.ADMIN.equals(role.getRoleCode())) {
				throw new ServiceException("内置管理员角色的编码不能修改。需要新角色时请新增一个");
			}
			if (!code.equals(exist.getRoleCode())) {
				throw new ServiceException("角色编码创建后不能修改。请新增一个角色，再删除原来的");
			}
			role.sanitizeForUpdate();
			role.setTenantId(exist.getTenantId());
			roleMapper.update(role);
		}
		syncRoleDept(role);
		return R.success("已保存。可以在列表里查看这个角色");
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
	@RepeatSubmit
	@PostMapping("/remove")
	public R<Void> remove(@RequestBody List<Long> ids) {
		if (ids == null || ids.isEmpty()) {
			throw new ServiceException("请先勾选要删除的角色");
		}
		List<SysRole> rows = new ArrayList<>();
		for (Long id : ids) {
			if (id == null) {
				continue;
			}
			SysRole role = roleMapper.selectOneById(id);
			if (role == null) {
				continue;
			}
			if (RoleConstants.ADMIN.equals(role.getRoleCode())) {
				throw new ServiceException("「" + role.getRoleName() + "」是内置管理员角色，不能删除。请改其他角色");
			}
			if (userRoleMapper.selectCountByQuery(QueryWrapper.create().eq("role_id", id)) > 0) {
				throw new ServiceException("还有人在用「" + role.getRoleName() + "」。请先到用户管理里换掉他们的角色，再删除");
			}
			rows.add(role);
		}
		if (rows.isEmpty()) {
			throw new ServiceException("这些角色不存在，或属于其他租户。请刷新列表后再删");
		}
		for (SysRole role : rows) {
			roleMenuMapper.deleteByQuery(QueryWrapper.create().eq("role_id", role.getId()));
			roleDeptMapper.deleteByQuery(QueryWrapper.create().eq("role_id", role.getId()));
			roleMapper.deleteById(role.getId());
		}
		return R.success("已删除。列表里不会再出现这些角色");
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
		// 先校验提交的 menuIds（不存在/越权/套餐外一律业务错误拒绝，不静默丢弃）；#85 保留的隐藏模块授权不经此校验
		assertMenuIdsGrantable(param.menuIds());
		List<SysMenu> allMenus = menuMapper.selectListByQuery(QueryWrapper.create());
		// 授权树不展示已关闭模块的菜单，提交上来的勾选自然不含它们：保留这部分既有授权，
		// 否则临时关掉 GIS/埋点/AI 时授权一次，重新开启后角色就丢了这些权限
		Set<Long> hidden = moduleMenuFilter.hiddenMenuIds(allMenus);
		LinkedHashSet<Long> target = new LinkedHashSet<>();
		if (!hidden.isEmpty()) {
			roleMenuMapper.selectListByQuery(QueryWrapper.create().eq("role_id", param.roleId())).stream()
				.map(SysRoleMenu::getMenuId)
				.filter(hidden::contains)
				.forEach(target::add);
		}
		if (param.menuIds() != null) {
			target.addAll(param.menuIds());
		}
		roleMenuMapper.deleteByQuery(QueryWrapper.create().eq("role_id", param.roleId()));
		for (Long menuId : target) {
			SysRoleMenu roleMenu = new SysRoleMenu();
			roleMenu.setRoleId(param.roleId());
			roleMenu.setMenuId(menuId);
			roleMenuMapper.insert(roleMenu);
		}
		return R.success("授权成功");
	}

	/** 角色归属校验：sys_role_menu/sys_role_dept 中间表无 tenant_id，必须先证角色属于当前租户（Flex 租户条件天然挡跨租户） */
	private void assertRoleInScope(Long roleId) {
		if (roleId == null || roleMapper.selectOneById(roleId) == null) {
			throw new ServiceException("这个角色不存在，或属于其他租户。请刷新列表后再授权");
		}
	}

	/**
	 * 提交的菜单 id 必须：存在且未删除；对操作者可授（超管=全量减停用模块；其他人=自身角色菜单减停用模块）；
	 * 且落在当前租户套餐内。任一不满足 → 业务错误，不静默丢弃。
	 */
	private void assertMenuIdsGrantable(List<Long> menuIds) {
		if (menuIds == null || menuIds.isEmpty()) {
			return;
		}
		LinkedHashSet<Long> submitted = new LinkedHashSet<>();
		for (Long id : menuIds) {
			if (id == null) {
				throw new ServiceException("有菜单不存在或已删除，请重新勾选后再授权");
			}
			submitted.add(id);
		}
		List<SysMenu> all = menuMapper.selectListByQuery(QueryWrapper.create());
		Map<Long, SysMenu> byId = new HashMap<>(all.size() * 2);
		for (SysMenu menu : all) {
			byId.put(menu.getId(), menu);
		}
		for (Long id : submitted) {
			if (!byId.containsKey(id)) {
				throw new ServiceException("有菜单不存在或已删除，请重新勾选后再授权");
			}
		}
		Set<Long> grantable = resolveGrantableMenuIds(all, byId);
		for (Long id : submitted) {
			if (!grantable.contains(id)) {
				throw new ServiceException("有菜单超出你的权限或租户套餐，不能授权。请只勾选你可见的菜单");
			}
		}
	}

	/** 操作者可授权菜单集合：与授权树同口径，并强制套餐边界。 */
	private Set<Long> resolveGrantableMenuIds(List<SysMenu> all, Map<Long, SysMenu> byId) {
		Set<Long> grantable = new HashSet<>();
		if (StpUtil.getRoleList().contains(RoleConstants.ADMIN)) {
			grantable.addAll(byId.keySet());
		} else {
			List<Long> roleIds = userRoleMapper.selectListByQuery(
					QueryWrapper.create().eq("user_id", StpUtil.getLoginIdAsLong()))
				.stream().map(SysUserRole::getRoleId).toList();
			if (!roleIds.isEmpty()) {
				roleMenuMapper.selectListByQuery(QueryWrapper.create().in("role_id", roleIds))
					.forEach(rm -> grantable.add(rm.getMenuId()));
			}
		}
		grantable.removeAll(moduleMenuFilter.hiddenMenuIds(all));
		Set<String> packageKeys = currentPackageKeys();
		if (packageKeys != null && !packageKeys.isEmpty()) {
			grantable.removeIf(id -> outsidePackage(byId.get(id), byId, packageKeys));
		}
		return grantable;
	}

	/** 当前租户套餐路由 name；平台租户/未绑套餐/空 keys → null（不限）。 */
	private Set<String> currentPackageKeys() {
		String tenantId = TenantContext.current();
		if (tenantId == null || TenantConstants.DEFAULT_TENANT_ID.equals(tenantId)) {
			return null;
		}
		SysTenant tenant = tenantValidator.loadTenant(tenantId);
		if (tenant == null || tenant.getPackageId() == null) {
			return null;
		}
		Set<String> keys = tenantValidator.loadPackageKeys(tenant.getPackageId());
		return keys == null || keys.isEmpty() ? null : keys;
	}

	/** 菜单（含无 path 的按钮）是否落在套餐外：与 TenantGuard 同口径。 */
	private static boolean outsidePackage(SysMenu menu, Map<Long, SysMenu> byId, Set<String> packageKeys) {
		if (menu == null) {
			return true;
		}
		String path = resolveProbePath(menu, byId);
		if (path == null || path.isBlank()) {
			return false;
		}
		Set<String> required = TenantPackageModules.gatedRouteNames(path);
		if (required == null) {
			return false;
		}
		return Collections.disjoint(required, packageKeys);
	}

	/** 按钮无 path：沿父链取最近的 path，供套餐判定。 */
	private static String resolveProbePath(SysMenu menu, Map<Long, SysMenu> byId) {
		SysMenu cur = menu;
		for (int i = 0; i < 64 && cur != null; i++) {
			if (cur.getPath() != null && !cur.getPath().isBlank()) {
				return cur.getPath();
			}
			Long parentId = cur.getParentId();
			if (parentId == null || parentId == 0L) {
				return null;
			}
			cur = byId.get(parentId);
		}
		return null;
	}
}
