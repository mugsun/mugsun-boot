package com.mugsun.boot.system.controller;

import cn.dev33.satoken.annotation.SaCheckLogin;
import cn.dev33.satoken.annotation.SaCheckPermission;
import com.mugsun.boot.common.module.ModuleMenuFilter;
import com.mugsun.boot.common.repeat.RepeatSubmit;
import com.mugsun.boot.system.entity.SysMenu;
import com.mugsun.boot.system.mapper.SysMenuMapper;
import com.mugsun.boot.system.mapper.SysRoleMenuMapper;
import com.mugsun.core.tool.api.R;
import com.mugsun.core.tool.exception.ServiceException;
import com.mugsun.core.tool.tree.TreeUtil;
import com.mybatisflex.core.query.QueryWrapper;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * 菜单管理
 */
@RestController
@RequestMapping("/system/menu")
@SaCheckLogin
public class SysMenuController {

	private static final Pattern PERMISSION = Pattern.compile("^[A-Za-z*][A-Za-z0-9_:*:.-]{0,127}$");

	private final SysMenuMapper menuMapper;
	private final SysRoleMenuMapper roleMenuMapper;
	private final ModuleMenuFilter moduleMenuFilter;

	public SysMenuController(SysMenuMapper menuMapper, SysRoleMenuMapper roleMenuMapper,
							 ModuleMenuFilter moduleMenuFilter) {
		this.menuMapper = menuMapper;
		this.roleMenuMapper = roleMenuMapper;
		this.moduleMenuFilter = moduleMenuFilter;
	}

	@GetMapping("/tree")
	public R<List<SysMenu>> tree(@RequestParam(required = false) String menuName,
								 @RequestParam(required = false) Integer isHide) {
		QueryWrapper query = QueryWrapper.create().orderBy("sort", true);
		// 查询条件（值走参数化绑定，LIKE 前后模糊）
		if (menuName != null && !menuName.isBlank()) {
			query.like("menu_name", menuName.trim());
		}
		if (isHide != null) {
			query.eq("is_hide", isHide);
		}
		List<SysMenu> all = menuMapper.selectListByQuery(query);
		// 菜单管理与角色授权弹窗共用本接口：未装配/已关闭模块的菜单（含按钮）不展示，防授权到不存在的页面。
		// 名称/隐藏条件会滤掉父链，带条件时须在全量菜单上算父链闭包
		boolean filtered = (menuName != null && !menuName.isBlank()) || isHide != null;
		java.util.Set<Long> hidden = moduleMenuFilter.hiddenMenuIds(
			filtered ? menuMapper.selectListByQuery(QueryWrapper.create()) : all);
		if (!hidden.isEmpty()) {
			all = all.stream().filter(m -> !hidden.contains(m.getId())).toList();
		}
		List<SysMenu> tree = new java.util.ArrayList<>(TreeUtil.build(all, 0L));
		// 条件过滤时父链可能被滤掉：父级不在结果集的节点提升为根，避免「查得出却看不见」
		if ((menuName != null && !menuName.isBlank()) || isHide != null) {
			java.util.Set<Long> ids = new java.util.HashSet<>();
			all.forEach(m -> ids.add(m.getId()));
			for (SysMenu node : all) {
				if (node.getParentId() != null && !node.getParentId().equals(0L) && !ids.contains(node.getParentId())) {
					node.setChildren(TreeUtil.build(all, node.getId()));
					tree.add(node);
				}
			}
		}
		return R.data(tree);
	}

	@GetMapping("/detail")
	public R<SysMenu> detail(@RequestParam Long id) {
		SysMenu menu = menuMapper.selectOneById(id);
		if (menu == null) {
			throw new ServiceException("这个菜单不存在。请刷新列表后再看");
		}
		return R.data(menu);
	}

	@SaCheckPermission("sys:menu:save")
	@RepeatSubmit
	@PostMapping("/submit")
	public R<Void> submit(@RequestBody SysMenu menu) {
		if (menu == null) {
			throw new ServiceException("请填写菜单内容后再保存");
		}
		String name = menu.getMenuName() == null ? "" : menu.getMenuName().trim();
		if (name.isEmpty()) {
			throw new ServiceException("请填写菜单名称");
		}
		if (name.length() > 64) {
			throw new ServiceException("菜单名称请控制在 64 字以内");
		}
		menu.setMenuName(name);
		String type = menu.getMenuType() == null ? "" : menu.getMenuType().trim();
		if (!type.equals("M") && !type.equals("C") && !type.equals("F")) {
			throw new ServiceException("请选择目录、菜单或按钮");
		}
		menu.setMenuType(type);
		long parentId = menu.getParentId() == null ? 0L : menu.getParentId();
		menu.setParentId(parentId);
		String path = menu.getPath() == null ? "" : menu.getPath().trim();
		if ("F".equals(type)) {
			path = "";
		} else if (path.isEmpty()) {
			throw new ServiceException("目录和菜单要填写路由地址，例如 /system/user");
		} else if (path.length() > 255 || path.chars().anyMatch(Character::isWhitespace)) {
			throw new ServiceException("路由地址请控制在 255 字以内，并且不要包含空格");
		} else if (menu.getIsExternal() != null && menu.getIsExternal() == 1) {
			if (!path.startsWith("http://") && !path.startsWith("https://")) {
				throw new ServiceException("外链地址要以 http:// 或 https:// 开头");
			}
		} else if (!path.startsWith("/")) {
			throw new ServiceException("路由地址要以 / 开头，例如 /system/user");
		}
		menu.setPath(path);
		String permission = menu.getPermission() == null ? "" : menu.getPermission().trim();
		if ("F".equals(type) && permission.isEmpty()) {
			throw new ServiceException("按钮要填写权限标识，例如 sys:user:list");
		}
		if (!permission.isEmpty() && !PERMISSION.matcher(permission).matches()) {
			throw new ServiceException("权限标识请用字母开头，只能包含字母、数字、冒号、下划线、点和中横线");
		}
		menu.setPermission(permission.isEmpty() ? null : permission);
		if (menu.getComponent() != null && menu.getComponent().length() > 255) {
			throw new ServiceException("组件路径请控制在 255 字以内");
		}
		if (menu.getIcon() != null && menu.getIcon().length() > 128) {
			throw new ServiceException("图标名称请控制在 128 字以内");
		}
		if (menu.getSort() == null) {
			menu.setSort(0);
		}
		if (menu.getSort() < 0 || menu.getSort() > 9999) {
			throw new ServiceException("排序请填 0 到 9999 的整数");
		}
		menu.setIsHide(flag(menu.getIsHide(), "是否隐藏"));
		menu.setIsKeepAlive(flag(menu.getIsKeepAlive(), "页面缓存"));
		menu.setIsExternal(flag(menu.getIsExternal(), "是否外链"));
		menu.setIsPublic(flag(menu.getIsPublic(), "是否公共"));
		if (parentId != 0L) {
			if (menu.getId() != null && parentId == menu.getId()) {
				throw new ServiceException("上级菜单不能是自己。请改成顶级，或选另一个菜单");
			}
			SysMenu parent = menuMapper.selectOneById(parentId);
			if (parent == null) {
				throw new ServiceException("上级菜单不存在。请刷新后再选");
			}
			if ("F".equals(parent.getMenuType())) {
				throw new ServiceException("按钮下面不能再加菜单。请改选目录或菜单作为上级");
			}
			if (menu.getId() != null && isDescendant(parentId, menu.getId())) {
				throw new ServiceException("上级不能选自己的下级。请改成顶级，或选另一个菜单");
			}
		}
		QueryWrapper sameName = QueryWrapper.create().eq("parent_id", parentId).eq("menu_name", name);
		if (menu.getId() != null) {
			sameName.ne("id", menu.getId());
		}
		if (menuMapper.selectCountByQuery(sameName) > 0) {
			throw new ServiceException("这个上级下面已经有同名菜单。请换一个名称，或编辑原来那个");
		}
		if (!permission.isEmpty()) {
			QueryWrapper samePerm = QueryWrapper.create().eq("permission", permission);
			if (menu.getId() != null) {
				samePerm.ne("id", menu.getId());
			}
			SysMenu current = menu.getId() == null ? null : menuMapper.selectOneById(menu.getId());
			boolean unchanged = current != null && permission.equals(current.getPermission());
			if (!unchanged && menuMapper.selectCountByQuery(samePerm) > 0) {
				throw new ServiceException("权限标识已存在。请换一个，避免两个按钮用同一个权限");
			}
		}
		if (menu.getId() == null) {
			menu.sanitizeForInsert();
			menuMapper.insert(menu);
		} else {
			if (menuMapper.selectOneById(menu.getId()) == null) {
				throw new ServiceException("这个菜单不存在。请刷新列表后再改");
			}
			menu.sanitizeForUpdate();
			menuMapper.update(menu);
		}
		return R.success("已保存。可以在列表里查看这个菜单");
	}

	@SaCheckPermission("sys:menu:remove")
	@RepeatSubmit
	@PostMapping("/remove")
	public R<Void> remove(@RequestBody List<Long> ids) {
		if (ids == null || ids.isEmpty()) {
			throw new ServiceException("请先勾选要删除的菜单");
		}
		List<SysMenu> rows = new ArrayList<>();
		boolean granted = false;
		for (Long id : ids) {
			if (id == null) {
				continue;
			}
			SysMenu menu = menuMapper.selectOneById(id);
			if (menu == null) {
				continue;
			}
			if (menuMapper.selectCountByQuery(QueryWrapper.create().eq("parent_id", id)) > 0) {
				throw new ServiceException("「" + menu.getMenuName() + "」下面还有下级菜单。请先删除下级，再删除这一级");
			}
			if (roleMenuMapper.selectCountByQuery(QueryWrapper.create().eq("menu_id", id)) > 0) {
				granted = true;
			}
			rows.add(menu);
		}
		if (rows.isEmpty()) {
			throw new ServiceException("这些菜单已经不在了。请刷新列表后再删");
		}
		for (SysMenu menu : rows) {
			roleMenuMapper.deleteByQuery(QueryWrapper.create().eq("menu_id", menu.getId()));
			menuMapper.deleteById(menu.getId());
		}
		if (granted) {
			return R.success("已删除。列表里不会再出现这些菜单，已经授给角色的这项权限也会一起去掉");
		}
		return R.success("已删除。列表里不会再出现这些菜单");
	}

	private int flag(Integer value, String label) {
		int flag = value == null ? 0 : value;
		if (flag != 0 && flag != 1) {
			throw new ServiceException(label + "只能选是或否");
		}
		return flag;
	}

	/** startId 的父链上是否含 targetId（成环判定：拟设父级的祖先里有我，则我成了自己的后代） */
	private boolean isDescendant(Long startId, Long targetId) {
		Long cur = startId;
		for (int i = 0; i < 64 && cur != null && !cur.equals(0L); i++) {
			if (cur.equals(targetId)) {
				return true;
			}
			SysMenu parent = menuMapper.selectOneById(cur);
			cur = parent == null ? null : parent.getParentId();
		}
		return false;
	}
}
