package com.mugsun.boot.system.controller;

import cn.dev33.satoken.annotation.SaCheckLogin;
import cn.dev33.satoken.stp.StpUtil;
import com.mugsun.boot.common.constant.RoleConstants;
import com.mugsun.boot.system.entity.SysMenu;
import com.mugsun.boot.system.entity.SysRoleMenu;
import com.mugsun.boot.system.entity.SysUserRole;
import com.mugsun.boot.system.mapper.SysMenuMapper;
import com.mugsun.boot.system.mapper.SysRoleMenuMapper;
import com.mugsun.boot.system.mapper.SysUserRoleMapper;
import com.mugsun.core.tool.api.R;
import com.mybatisflex.core.query.QueryWrapper;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 后端菜单驱动：当前用户菜单树（前端动态路由数据源，对齐 art-design-pro 后端模式契约）。
 * 内置 admin 角色 → 全量菜单；其他用户 → 公共菜单(is_public=1) ∪ 角色授权菜单，父链自动补齐、空目录剔除。
 */
@RestController
@RequestMapping("/v3/system")
@SaCheckLogin
public class MenuRouteController {

	private final SysMenuMapper menuMapper;
	private final SysUserRoleMapper userRoleMapper;
	private final SysRoleMenuMapper roleMenuMapper;
	private final com.mugsun.boot.common.module.GisModuleStatus gisModuleStatus;
	private final com.mugsun.boot.common.module.TrackModuleStatus trackModuleStatus;

	public MenuRouteController(SysMenuMapper menuMapper, SysUserRoleMapper userRoleMapper,
							   SysRoleMenuMapper roleMenuMapper,
							   com.mugsun.boot.common.module.GisModuleStatus gisModuleStatus,
							   com.mugsun.boot.common.module.TrackModuleStatus trackModuleStatus) {
		this.menuMapper = menuMapper;
		this.userRoleMapper = userRoleMapper;
		this.roleMenuMapper = roleMenuMapper;
		this.gisModuleStatus = gisModuleStatus;
		this.trackModuleStatus = trackModuleStatus;
	}

	@GetMapping("/menus")
	public R<List<Map<String, Object>>> menus() {
		// 目录(M)/菜单(C) 全量（含隐藏——隐藏由前端 meta.isHide 呈现，路由仍注册）
		List<SysMenu> all = menuMapper.selectListByQuery(
			QueryWrapper.create().in("menu_type", List.of("M", "C")).orderBy("sort", true));
		Map<Long, SysMenu> byId = new HashMap<>();
		for (SysMenu m : all) {
			byId.put(m.getId(), m);
		}
		boolean platformSuperAdmin = com.mugsun.boot.tenant.TenantContext.isPlatformSuperAdmin();
		// 可见 id 集：admin 全量；其余 授权 ∪ 公共，父链补齐
		java.util.Set<Long> visible = new java.util.HashSet<>();
		if (StpUtil.getRoleList().contains(RoleConstants.ADMIN)) {
			visible.addAll(byId.keySet());
		} else {
			List<Long> roleIds = userRoleMapper.selectListByQuery(
					QueryWrapper.create().eq("user_id", StpUtil.getLoginIdAsLong()))
				.stream().map(SysUserRole::getRoleId).toList();
			if (!roleIds.isEmpty()) {
				roleMenuMapper.selectListByQuery(QueryWrapper.create().in("role_id", roleIds))
					.forEach(rm -> visible.add(rm.getMenuId()));
			}
			for (SysMenu m : all) {
				if (Integer.valueOf(1).equals(m.getIsPublic())) {
					visible.add(m.getId());
				}
			}
			// 父链补齐（授权的叶子没目录壳就看不见）
			for (Long id : new java.util.HashSet<>(visible)) {
				SysMenu cur = byId.get(id);
				while (cur != null && cur.getParentId() != null && cur.getParentId() != 0) {
					visible.add(cur.getParentId());
					cur = byId.get(cur.getParentId());
				}
			}
		}
		// 非平台超管剔除平台专属菜单（租户/开放平台/监控/任务等平台设施页面对其 403，菜单不展示防死链）
		if (!platformSuperAdmin) {
			for (SysMenu m : all) {
				String probe = m.getComponent() != null && !m.getComponent().isBlank()
					? m.getComponent() : m.getPath();
				if (probe != null && com.mugsun.boot.tenant.TenantPackageModules.isPlatformOnly(probe)) {
					visible.remove(m.getId());
				}
			}
		}
		if (!gisModuleStatus.enabled()) {
			for (SysMenu m : all) {
				String path = m.getPath();
				if (path != null && (path.equals("/gis") || path.startsWith("/gis/"))) {
					visible.remove(m.getId());
				}
			}
		}
		if (!trackModuleStatus.enabled()) {
			for (SysMenu m : all) {
				String path = m.getPath();
				if (path != null && (path.equals("/track") || path.startsWith("/track/"))) {
					visible.remove(m.getId());
				}
			}
		}
		// 建树（父不存在的节点提升为根）+ 空目录剔除
		List<Map<String, Object>> tree = buildTree(0L, byId, visible);
		return R.data(tree);
	}

	private List<Map<String, Object>> buildTree(Long parentId, Map<Long, SysMenu> byId, java.util.Set<Long> visible) {
		List<Map<String, Object>> result = new ArrayList<>();
		for (SysMenu m : byId.values()) {
			Long pid = m.getParentId() == null ? 0L : m.getParentId();
			// 顶级：pid=0；父不可见/不存在时提升为根（防"查得出却看不见"）
			boolean isRoot = pid == 0L || !visible.contains(pid) || !byId.containsKey(pid);
			Long effectivePid = isRoot ? 0L : pid;
			if (!visible.contains(m.getId()) || !effectivePid.equals(parentId)) {
				continue;
			}
			List<Map<String, Object>> children = buildTree(m.getId(), byId, visible);
			// 目录（无 path 的 M）无可见子则剔除
			boolean isDir = "M".equals(m.getMenuType());
			if (isDir && children.isEmpty()) {
				continue;
			}
			result.add(toRoute(m, parentId, byId, children));
		}
		result.sort((a, b) -> Integer.compare((Integer) a.get("sort"), (Integer) b.get("sort")));
		return result;
	}

	/** 转前端 AppRouteRecord：子路径相对化（前端 normalizeMenuPaths 负责拼全）、目录组件落布局 */
	private Map<String, Object> toRoute(SysMenu m, Long parentId, Map<Long, SysMenu> byId,
										List<Map<String, Object>> children) {
		Map<String, Object> node = new LinkedHashMap<>();
		String fullPath = m.getPath() == null ? "" : m.getPath();
		String parentPath = parentId == 0L ? null : byId.get(parentId).getPath();
		String relPath = parentPath != null && fullPath.startsWith(parentPath + "/")
			? fullPath.substring(parentPath.length() + 1)
			: fullPath;
		node.put("id", m.getId());
		node.put("parentId", parentId);
		node.put("path", relPath);
		node.put("name", routeName(fullPath, m.getId()));
		node.put("component", m.getComponent() != null && !m.getComponent().isBlank()
			? m.getComponent()
			: ("M".equals(m.getMenuType()) ? "/index/index" : fullPath));
		Map<String, Object> meta = new LinkedHashMap<>();
		meta.put("title", m.getMenuName());
		meta.put("icon", m.getIcon());
		meta.put("isHide", Integer.valueOf(1).equals(m.getIsHide()));
		meta.put("keepAlive", !Integer.valueOf(0).equals(m.getIsKeepAlive()));
		if (Integer.valueOf(1).equals(m.getIsExternal())) {
			meta.put("isExternal", true);
			meta.put("link", fullPath);
		}
		node.put("meta", meta);
		node.put("sort", m.getSort() == null ? 0 : m.getSort());
		if (!children.isEmpty()) {
			node.put("children", children);
		}
		return node;
	}

	/** 路由名：按全路径派生唯一稳定名（/system/user → SystemUser；兜底 Route_<id>） */
	private String routeName(String fullPath, Long id) {
		if (fullPath == null || fullPath.isBlank()) {
			return "Route_" + id;
		}
		StringBuilder sb = new StringBuilder();
		for (String seg : fullPath.split("/")) {
			if (seg.isBlank()) {
				continue;
			}
			for (String part : seg.split("-")) {
				if (!part.isBlank()) {
					sb.append(Character.toUpperCase(part.charAt(0))).append(part.substring(1));
				}
			}
		}
		return sb.length() == 0 ? "Route_" + id : sb.toString();
	}
}
