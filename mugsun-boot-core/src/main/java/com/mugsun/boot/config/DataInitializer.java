package com.mugsun.boot.config;

import com.mugsun.boot.common.constant.RoleConstants;
import com.mugsun.boot.common.constant.TenantConstants;
import com.mugsun.boot.common.constant.UserConstants;
import com.mugsun.boot.tenant.TenantContext;
import com.mugsun.boot.system.entity.SysMenu;
import com.mugsun.boot.system.entity.SysRole;
import com.mugsun.boot.system.entity.SysRoleMenu;
import com.mugsun.boot.system.entity.SysUser;
import com.mugsun.boot.system.entity.SysUserRole;
import com.mugsun.boot.system.mapper.SysMenuMapper;
import com.mugsun.boot.system.mapper.SysRoleMapper;
import com.mugsun.boot.system.mapper.SysRoleMenuMapper;
import com.mugsun.boot.system.mapper.SysUserMapper;
import com.mugsun.boot.system.mapper.SysUserRoleMapper;
import com.mybatisflex.core.query.QueryWrapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

/**
 * 初始化数据：首次启动创建超级管理员及其角色、菜单权限；
 * 幂等播种内置普通用户角色（自助注册默认归属）。
 */
@Component
public class DataInitializer implements CommandLineRunner {

	private static final Logger log = LoggerFactory.getLogger(DataInitializer.class);

	private final SysUserMapper userMapper;
	private final SysRoleMapper roleMapper;
	private final SysMenuMapper menuMapper;
	private final SysUserRoleMapper userRoleMapper;
	private final SysRoleMenuMapper roleMenuMapper;
	private final PasswordEncoder passwordEncoder;
	private final com.mugsun.boot.security.SecurityPolicyService securityPolicyService;

	/** 开发/联调：幂等播种低权账号 fronttest（sec 探针与 e2e 约定密码 123456） */
	@Value("${mugsun.lab.seed-fronttest:false}")
	private boolean seedFronttest;

	public DataInitializer(SysUserMapper userMapper, SysRoleMapper roleMapper, SysMenuMapper menuMapper,
						   SysUserRoleMapper userRoleMapper, SysRoleMenuMapper roleMenuMapper,
						   PasswordEncoder passwordEncoder,
						   com.mugsun.boot.security.SecurityPolicyService securityPolicyService) {
		this.userMapper = userMapper;
		this.roleMapper = roleMapper;
		this.menuMapper = menuMapper;
		this.userRoleMapper = userRoleMapper;
		this.roleMenuMapper = roleMenuMapper;
		this.passwordEncoder = passwordEncoder;
		this.securityPolicyService = securityPolicyService;
	}

	@Override
	public void run(String... args) {
		// 启动初始化写平台租户表，无会话上下文——经中心忽略入口显式声明，规避 fail-closed
		TenantContext.ignore(() -> {
			seed();
			seedCommonRole();
			seedFronttestUser();
			reanchorSeedMenus();
			return null;
		});
	}

	/**
	 * 联调低权用户：挂普通用户角色（无管理写权限），供 api-probe sec / 浏览器越权对照。
	 */
	private void seedFronttestUser() {
		if (!seedFronttest) {
			return;
		}
		if (userMapper.selectCountByQuery(QueryWrapper.create().eq("username", "fronttest")) > 0) {
			return;
		}
		seedCommonRole();
		SysRole common = roleMapper.selectOneByQuery(QueryWrapper.create()
			.eq("role_code", RoleConstants.USER)
			.eq("tenant_id", TenantConstants.DEFAULT_TENANT_ID));
		if (common == null) {
			return;
		}
		SysUser u = new SysUser();
		u.setUsername("fronttest");
		u.setPassword(passwordEncoder.encode("123456"));
		u.setNickname("前端测试");
		u.setStatus(1);
		u.setIsLeader(0);
		u.setTenantId(TenantConstants.DEFAULT_TENANT_ID);
		try {
			userMapper.insert(u);
			SysUserRole ur = new SysUserRole();
			ur.setUserId(u.getId());
			ur.setRoleId(common.getId());
			userRoleMapper.insert(ur);
		} catch (Exception e) {
			log.warn("播种 fronttest 跳过（可能已存在或唯一约束差异）：{}", e.getMessage());
		}
	}

	/**
	 * 播种内置普通用户角色（幂等，存量库也补）：自助注册的默认归属角色，
	 * 仅本人数据范围、无任何权限码——注册即可登录见工作台，又不碰管理面。
	 */
	private void seedCommonRole() {
		long exists = roleMapper.selectCountByQuery(QueryWrapper.create()
			.eq("role_code", RoleConstants.USER)
			.eq("tenant_id", TenantConstants.DEFAULT_TENANT_ID));
		if (exists > 0) {
			return;
		}
		SysRole commonRole = new SysRole();
		commonRole.setRoleName("普通用户");
		commonRole.setRoleCode(RoleConstants.USER);
		commonRole.setSort(9);
		commonRole.setDataScope(com.mugsun.boot.common.constant.DataScopeConstants.SELF);
		commonRole.setTenantId(TenantConstants.DEFAULT_TENANT_ID);
		roleMapper.insert(commonRole);
	}

	/**
	 * 种子按钮/授权重锚（幂等）：V39/V45/V46 迁移含硬编码雪花 ID，全新环境播种菜单 ID 不同会悬空——
	 * 启动时按业务键（permission/role_code）把种子菜单挂回真实父菜单、授权挂回 datatest 角色。
	 */
	private void reanchorSeedMenus() {
		String menu = BizTables.of("sys_menu");
		String role = BizTables.of("sys_role");
		String roleMenu = BizTables.of("sys_role_menu");
		String lim = com.mugsun.boot.gen.DbDialects.current().limitOne();
		com.mybatisflex.core.row.Db.updateBySql(
			"UPDATE " + menu + " SET parent_id = (SELECT id FROM " + menu
				+ " WHERE permission = 'sys:user:list' AND is_deleted = 0" + lim + ") "
				+ "WHERE permission IN ('sys:user:add','sys:user:edit','sys:user:remove','sys:user:grant','sys:user:reset',"
				+ "'sys:user:phone','sys:user:phone:plain','sys:user:idcard','sys:user:idcard:plain') AND is_deleted = 0 "
				+ "AND EXISTS (SELECT 1 FROM " + menu + " WHERE permission = 'sys:user:list' AND is_deleted = 0)");
		com.mybatisflex.core.row.Db.updateBySql(
			"UPDATE " + menu + " SET parent_id = (SELECT parent_id FROM " + menu
				+ " WHERE permission = 'sys:user:list' AND is_deleted = 0" + lim + ") "
				+ "WHERE permission = 'sys:gen:list' AND is_deleted = 0 "
				+ "AND EXISTS (SELECT 1 FROM " + menu + " WHERE permission = 'sys:user:list' AND is_deleted = 0)");
		com.mybatisflex.core.row.Db.updateBySql(
			"UPDATE " + roleMenu + " SET role_id = (SELECT id FROM " + role
				+ " WHERE role_code = 'datatest' AND is_deleted = 0" + lim + ") "
				+ "WHERE menu_id IN (SELECT id FROM " + menu
				+ " WHERE permission IN ('sys:user:add','sys:user:phone') AND is_deleted = 0) "
				+ "AND is_deleted = 0 AND role_id NOT IN (SELECT id FROM " + role + ") "
				+ "AND EXISTS (SELECT 1 FROM " + role + " WHERE role_code = 'datatest' AND is_deleted = 0)");
	}

	private void seed() {
		if (userMapper.selectCountByQuery(QueryWrapper.create().eq("username", UserConstants.ADMIN_USERNAME)) > 0) {
			return;
		}
		// 角色
		SysRole adminRole = new SysRole();
		adminRole.setRoleName("超级管理员");
		adminRole.setRoleCode(RoleConstants.ADMIN);
		adminRole.setSort(1);
		adminRole.setDataScope(com.mugsun.boot.common.constant.DataScopeConstants.ALL);
		adminRole.setTenantId(TenantConstants.DEFAULT_TENANT_ID);
		roleMapper.insert(adminRole);
		// 菜单：系统管理 > 用户管理（V60 迁移可能已播种，按 path 幂等复用，防重复目录）
		SysMenu systemMenu = menuMapper.selectOneByQuery(QueryWrapper.create().eq("path", "/system"));
		if (systemMenu == null) {
			systemMenu = new SysMenu();
			systemMenu.setParentId(0L);
			systemMenu.setMenuName("系统管理");
			systemMenu.setPath("/system");
			systemMenu.setMenuType("M");
			systemMenu.setSort(1);
			menuMapper.insert(systemMenu);
		}
		SysMenu userMenu = menuMapper.selectOneByQuery(QueryWrapper.create().eq("path", "/system/user"));
		if (userMenu == null) {
			userMenu = new SysMenu();
			userMenu.setParentId(systemMenu.getId());
			userMenu.setMenuName("用户管理");
			userMenu.setPath("/system/user");
			userMenu.setMenuType("M");
			userMenu.setPermission("sys:user:list");
			userMenu.setSort(1);
			menuMapper.insert(userMenu);
		}
		// 管理员用户
		SysUser admin = new SysUser();
		admin.setUsername(UserConstants.ADMIN_USERNAME);
		admin.setPassword(passwordEncoder.encode(securityPolicyService.getInitPassword()));
		admin.setNickname("超级管理员");
		admin.setStatus(1);
		admin.setIsLeader(0);
		admin.setTenantId(TenantConstants.DEFAULT_TENANT_ID);
		userMapper.insert(admin);
		// 用户-角色
		SysUserRole userRole = new SysUserRole();
		userRole.setUserId(admin.getId());
		userRole.setRoleId(adminRole.getId());
		userRoleMapper.insert(userRole);
		// 角色-菜单
		for (Long menuId : new Long[]{systemMenu.getId(), userMenu.getId()}) {
			SysRoleMenu roleMenu = new SysRoleMenu();
			roleMenu.setRoleId(adminRole.getId());
			roleMenu.setMenuId(menuId);
			roleMenuMapper.insert(roleMenu);
		}
	}
}
