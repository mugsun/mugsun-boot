package com.mugsun.boot.system.controller;

import cn.dev33.satoken.annotation.SaCheckLogin;
import cn.dev33.satoken.annotation.SaCheckPermission;
import cn.dev33.satoken.stp.StpUtil;
import com.mugsun.boot.common.constant.RoleConstants;
import com.mugsun.boot.log.OperationLog;
import com.mugsun.boot.system.entity.SysMenu;
import com.mugsun.boot.system.entity.SysRoleMenu;
import com.mugsun.boot.system.entity.SysTopMenu;
import com.mugsun.boot.system.entity.SysTopMenuMenu;
import com.mugsun.boot.system.entity.SysUserRole;
import com.mugsun.boot.system.mapper.SysMenuMapper;
import com.mugsun.boot.system.mapper.SysRoleMenuMapper;
import com.mugsun.boot.system.mapper.SysTopMenuMapper;
import com.mugsun.boot.system.mapper.SysTopMenuMenuMapper;
import com.mugsun.boot.system.mapper.SysUserRoleMapper;
import com.mugsun.boot.tenant.TenantContext;
import com.mugsun.core.tool.api.R;
import com.mugsun.core.tool.exception.ServiceException;
import com.mugsun.core.tool.tree.TreeUtil;
import com.mybatisflex.core.paginate.Page;
import com.mybatisflex.core.query.QueryWrapper;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 顶部菜单方案：保存、配置包含的菜单、指定主页。侧栏按当前方案过滤。
 */
@RestController
@RequestMapping("/system/top-menu")
@SaCheckLogin
public class SysTopMenuController {

	private static final java.util.regex.Pattern CODE = java.util.regex.Pattern.compile("^[A-Za-z0-9_]{1,64}$");

	private final SysTopMenuMapper topMenuMapper;
	private final SysTopMenuMenuMapper linkMapper;
	private final SysMenuMapper menuMapper;
	private final SysUserRoleMapper userRoleMapper;
	private final SysRoleMenuMapper roleMenuMapper;

	public SysTopMenuController(SysTopMenuMapper topMenuMapper, SysTopMenuMenuMapper linkMapper,
								SysMenuMapper menuMapper, SysUserRoleMapper userRoleMapper,
								SysRoleMenuMapper roleMenuMapper) {
		this.topMenuMapper = topMenuMapper;
		this.linkMapper = linkMapper;
		this.menuMapper = menuMapper;
		this.userRoleMapper = userRoleMapper;
		this.roleMenuMapper = roleMenuMapper;
	}

	@GetMapping("/page")
	@SaCheckPermission("sys:top-menu:list")
	public R<Page<SysTopMenu>> page(@RequestParam(defaultValue = "1") long pageNum,
									@RequestParam(defaultValue = "10") long pageSize,
									@RequestParam(required = false) String name) {
		QueryWrapper query = QueryWrapper.create().orderBy("sort", true).orderBy("id", true);
		if (name != null && !name.isBlank()) {
			query.like("name", name.trim());
		}
		return R.data(topMenuMapper.paginate(pageNum, pageSize, query));
	}

	@GetMapping("/detail")
	@SaCheckPermission("sys:top-menu:list")
	public R<SysTopMenu> detail(@RequestParam Long id) {
		SysTopMenu row = requireOwn(id);
		return R.data(row);
	}

	@PostMapping("/submit")
	@SaCheckPermission("sys:top-menu:save")
	@OperationLog("保存顶部菜单方案")
	@Transactional(rollbackFor = Exception.class)
	public R<Void> submit(@RequestBody SysTopMenu body) {
		String name = body.getName() == null ? "" : body.getName().trim();
		String code = body.getCode() == null ? "" : body.getCode().trim();
		if (name.isEmpty()) {
			throw new ServiceException("请填写方案名称");
		}
		if (name.length() > 64) {
			throw new ServiceException("方案名称请控制在 64 字以内");
		}
		if (code.isEmpty()) {
			throw new ServiceException("请填写方案编码");
		}
		if (!CODE.matcher(code).matches()) {
			throw new ServiceException("编码请使用字母、数字或下划线，最长 64 位");
		}
		String path = body.getPath() == null ? null : body.getPath().trim();
		if (path != null && path.length() > 128) {
			throw new ServiceException("打开地址请控制在 128 字以内");
		}
		String icon = body.getIcon() == null ? null : body.getIcon().trim();
		if (icon != null && icon.length() > 64) {
			throw new ServiceException("图标名请控制在 64 字以内");
		}
		assertCodeFree(code, body.getId());
		boolean asHome = body.getIsMain() != null && body.getIsMain() == 1;
		if (body.getId() == null) {
			long others = topMenuMapper.selectCountByQuery(QueryWrapper.create());
			SysTopMenu row = new SysTopMenu();
			row.setName(name);
			row.setCode(code);
			row.setIcon(icon == null || icon.isEmpty() ? null : icon);
			row.setPath(path == null || path.isEmpty() ? null : path);
			row.setSort(body.getSort() == null ? 0 : body.getSort());
			row.setIsMain(others == 0 || asHome ? 1 : 0);
			row.setTenantId(TenantContext.current());
			row.sanitizeForInsert();
			if (row.getIsMain() == 1) {
				clearHome();
			}
			topMenuMapper.insert(row);
			return R.success(row.getIsMain() == 1 ? "已保存，并设为进入系统时的默认方案" : "已保存");
		}
		SysTopMenu exist = requireOwn(body.getId());
		exist.setName(name);
		exist.setCode(code);
		exist.setIcon(icon == null || icon.isEmpty() ? null : icon);
		exist.setPath(path == null || path.isEmpty() ? null : path);
		exist.setSort(body.getSort() == null ? 0 : body.getSort());
		if (asHome) {
			clearHome();
			exist.setIsMain(1);
		}
		exist.setTenantId(null);
		exist.sanitizeForUpdate();
		topMenuMapper.update(exist);
		return R.success(asHome ? "已保存，并设为进入系统时的默认方案" : "已保存");
	}

	@PostMapping("/remove")
	@SaCheckPermission("sys:top-menu:remove")
	@OperationLog("删除顶部菜单方案")
	@Transactional(rollbackFor = Exception.class)
	public R<Void> remove(@RequestBody List<Long> ids) {
		if (ids == null || ids.isEmpty()) {
			throw new ServiceException("请选择要删除的方案");
		}
		boolean removedHome = false;
		for (Long id : ids) {
			SysTopMenu row = requireOwn(id);
			if (row.getIsMain() != null && row.getIsMain() == 1) {
				removedHome = true;
			}
			topMenuMapper.deleteById(id);
			linkMapper.deleteByQuery(QueryWrapper.create().eq("top_menu_id", id));
		}
		if (removedHome) {
			SysTopMenu next = topMenuMapper.selectOneByQuery(
				QueryWrapper.create().orderBy("sort", true).orderBy("id", true).limit(1));
			if (next != null) {
				next.setIsMain(1);
				next.sanitizeForUpdate();
				topMenuMapper.update(next);
				return R.success("已删除。主页已改到「" + next.getName() + "」");
			}
		}
		return R.success("已删除");
	}

	/** 菜单树 + 当前方案已勾选的菜单，供配置弹窗回显。 */
	@GetMapping("/menus")
	@SaCheckPermission("sys:top-menu:grant")
	public R<Map<String, Object>> menus(@RequestParam Long id) {
		requireOwn(id);
		List<SysMenu> all = menuMapper.selectListByQuery(
			QueryWrapper.create().in("menu_type", List.of("M", "C")).orderBy("sort", true));
		List<String> checked = linkMapper.selectListByQuery(QueryWrapper.create().eq("top_menu_id", id))
			.stream().map(link -> String.valueOf(link.getMenuId())).toList();
		Map<String, Object> data = new LinkedHashMap<>();
		data.put("tree", TreeUtil.build(new ArrayList<>(all), 0L));
		data.put("checked", checked);
		return R.data(data);
	}

	@PostMapping("/grant")
	@SaCheckPermission("sys:top-menu:grant")
	@OperationLog("配置顶部菜单")
	@Transactional(rollbackFor = Exception.class)
	public R<Void> grant(@RequestBody Map<String, Object> body) {
		Long id = longOf(body.get("id"));
		if (id == null) {
			throw new ServiceException("请选择要配置的方案");
		}
		requireOwn(id);
		List<Long> menuIds = longList(body.get("menuIds"));
		if (menuIds.isEmpty()) {
			linkMapper.deleteByQuery(QueryWrapper.create().eq("top_menu_id", id));
			return R.success("已清空。切换到该方案时侧栏不会显示页面，请重新勾选菜单");
		}
		Set<Long> existIds = new HashSet<>();
		menuMapper.selectListByIds(menuIds).forEach(menu -> existIds.add(menu.getId()));
		if (existIds.isEmpty()) {
			throw new ServiceException("所选菜单不存在或已删除，请重新勾选");
		}
		linkMapper.deleteByQuery(QueryWrapper.create().eq("top_menu_id", id));
		for (Long menuId : existIds) {
			SysTopMenuMenu link = new SysTopMenuMenu();
			link.setTopMenuId(id);
			link.setMenuId(menuId);
			link.sanitizeForInsert();
			linkMapper.insert(link);
		}
		int skipped = menuIds.size() - existIds.size();
		if (skipped > 0) {
			return R.success("已保存 " + existIds.size() + " 个菜单，有 " + skipped + " 个已删除已跳过");
		}
		return R.success("已保存，切换到该方案后侧栏只显示勾选的菜单");
	}

	@PostMapping("/home")
	@SaCheckPermission("sys:top-menu:save")
	@OperationLog("设置顶部菜单主页")
	@Transactional(rollbackFor = Exception.class)
	public R<Void> home(@RequestBody Map<String, Object> body) {
		Long id = longOf(body.get("id"));
		if (id == null) {
			throw new ServiceException("请选择要设为主页的方案");
		}
		SysTopMenu row = requireOwn(id);
		clearHome();
		row.setIsMain(1);
		row.setTenantId(null);
		row.sanitizeForUpdate();
		topMenuMapper.update(row);
		return R.success("已设为主页。进入系统时默认打开「" + row.getName() + "」");
	}

	/** 当前用户可见的方案。菜单已展开子级，并去掉当前账号看不到的项。 */
	@GetMapping("/mine")
	public R<List<Map<String, Object>>> mine() {
		List<SysTopMenu> rows = topMenuMapper.selectListByQuery(
			QueryWrapper.create().orderBy("sort", true).orderBy("id", true));
		if (rows.isEmpty()) {
			return R.data(List.of());
		}
		List<SysMenu> all = menuMapper.selectListByQuery(
			QueryWrapper.create().in("menu_type", List.of("M", "C")));
		Map<Long, SysMenu> byId = new HashMap<>();
		for (SysMenu menu : all) {
			byId.put(menu.getId(), menu);
		}
		Set<Long> visible = visibleMenuIds(byId);
		List<Map<String, Object>> result = new ArrayList<>();
		for (SysTopMenu row : rows) {
			Set<Long> granted = new HashSet<>();
			linkMapper.selectListByQuery(QueryWrapper.create().eq("top_menu_id", row.getId()))
				.forEach(link -> granted.add(link.getMenuId()));
			List<String> menuIds = new ArrayList<>();
			for (SysMenu menu : all) {
				if (covered(menu, granted, byId) && visible.contains(menu.getId())) {
					menuIds.add(String.valueOf(menu.getId()));
				}
			}
			Map<String, Object> item = new LinkedHashMap<>();
			item.put("id", String.valueOf(row.getId()));
			item.put("name", row.getName());
			item.put("code", row.getCode());
			item.put("icon", row.getIcon());
			item.put("path", row.getPath());
			item.put("home", row.getIsMain() != null && row.getIsMain() == 1);
			item.put("menuIds", menuIds);
			result.add(item);
		}
		return R.data(result);
	}

	private void assertCodeFree(String code, Long selfId) {
		QueryWrapper query = QueryWrapper.create().eq("code", code);
		if (selfId != null) {
			query.ne("id", selfId);
		}
		if (topMenuMapper.selectCountByQuery(query) > 0) {
			throw new ServiceException("方案编码已存在，请换一个");
		}
	}

	private void clearHome() {
		List<SysTopMenu> homes = topMenuMapper.selectListByQuery(QueryWrapper.create().eq("is_main", 1));
		for (SysTopMenu home : homes) {
			home.setIsMain(0);
			home.sanitizeForUpdate();
			topMenuMapper.update(home);
		}
	}

	private SysTopMenu requireOwn(Long id) {
		SysTopMenu row = topMenuMapper.selectOneById(id);
		if (row == null) {
			throw new ServiceException("方案不存在或已删除");
		}
		return row;
	}

	private Set<Long> visibleMenuIds(Map<Long, SysMenu> byId) {
		if (StpUtil.getRoleList().contains(RoleConstants.ADMIN)) {
			return new HashSet<>(byId.keySet());
		}
		Set<Long> visible = new HashSet<>();
		List<Long> roleIds = userRoleMapper.selectListByQuery(
				QueryWrapper.create().eq("user_id", StpUtil.getLoginIdAsLong()))
			.stream().map(SysUserRole::getRoleId).toList();
		if (!roleIds.isEmpty()) {
			roleMenuMapper.selectListByQuery(QueryWrapper.create().in("role_id", roleIds))
				.forEach(link -> visible.add(link.getMenuId()));
		}
		for (SysMenu menu : byId.values()) {
			if (Integer.valueOf(1).equals(menu.getIsPublic())) {
				visible.add(menu.getId());
			}
		}
		for (Long id : new HashSet<>(visible)) {
			SysMenu cur = byId.get(id);
			while (cur != null && cur.getParentId() != null && cur.getParentId() != 0L) {
				visible.add(cur.getParentId());
				cur = byId.get(cur.getParentId());
			}
		}
		return visible;
	}

	/** 自身被勾选，或任一上级被勾选。 */
	private boolean covered(SysMenu menu, Set<Long> granted, Map<Long, SysMenu> byId) {
		if (granted.contains(menu.getId())) {
			return true;
		}
		Long parentId = menu.getParentId();
		for (int i = 0; i < 64 && parentId != null && parentId != 0L; i++) {
			if (granted.contains(parentId)) {
				return true;
			}
			SysMenu parent = byId.get(parentId);
			parentId = parent == null ? null : parent.getParentId();
		}
		return false;
	}

	private static Long longOf(Object raw) {
		if (raw == null || String.valueOf(raw).isBlank()) {
			return null;
		}
		try {
			return Long.valueOf(String.valueOf(raw));
		} catch (NumberFormatException ex) {
			throw new ServiceException("编号不正确，请刷新页面后重试");
		}
	}

	private static List<Long> longList(Object raw) {
		if (!(raw instanceof List<?> list)) {
			return List.of();
		}
		List<Long> ids = new ArrayList<>();
		for (Object item : list) {
			Long id = longOf(item);
			if (id != null) {
				ids.add(id);
			}
		}
		return ids;
	}
}
