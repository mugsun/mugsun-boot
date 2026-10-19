package com.mugsun.boot.system.controller;

import com.mugsun.boot.common.module.ModuleMenuFilter;
import com.mugsun.boot.common.module.ModuleMenuFilterTest;
import com.mugsun.boot.system.entity.SysMenu;
import com.mugsun.boot.system.entity.SysRole;
import com.mugsun.boot.system.entity.SysRoleMenu;
import com.mugsun.boot.system.mapper.SysMenuMapper;
import com.mugsun.boot.system.mapper.SysRoleDeptMapper;
import com.mugsun.boot.system.mapper.SysRoleMapper;
import com.mugsun.boot.system.mapper.SysRoleMenuMapper;
import com.mugsun.boot.system.mapper.SysUserRoleMapper;
import com.mugsun.boot.system.payload.GrantParam;
import com.mybatisflex.core.query.QueryWrapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 菜单管理树（/system/menu/tree，角色授权弹窗也用它）与角色授权（/system/role/grant）对可选模块的过滤。
 */
class ModuleMenuTreeFilterTest {

	private static ModuleMenuFilter filter(boolean gis, boolean track, boolean ai) {
		return ModuleMenuFilterTest.filter(gis, track, ai);
	}

	private static List<SysMenu> sample() {
		List<SysMenu> list = ModuleMenuFilterTest.sample();
		List<SysMenu> copy = new ArrayList<>();
		for (SysMenu s : list) {
			SysMenu c = new SysMenu();
			c.setId(s.getId());
			c.setParentId(s.getParentId());
			c.setPath(s.getPath());
			c.setMenuType(s.getMenuType());
			c.setMenuName(s.getPath() == null ? "按钮" + s.getId() : s.getPath());
			copy.add(c);
		}
		return copy;
	}

	private static List<Long> flatten(List<SysMenu> tree) {
		List<Long> ids = new ArrayList<>();
		for (SysMenu n : tree) {
			ids.add(n.getId());
			if (n.getChildren() != null) {
				ids.addAll(flatten(n.getChildren()));
			}
		}
		return ids;
	}

	@Test
	@DisplayName("菜单管理树：GIS、AI 关闭时整棵子树（含按钮）不出现")
	void menuTreeHidesDisabledModules() {
		SysMenuMapper menuMapper = mock(SysMenuMapper.class);
		when(menuMapper.selectListByQuery(any(QueryWrapper.class))).thenAnswer(inv -> sample());
		SysMenuController controller = new SysMenuController(menuMapper, mock(SysRoleMenuMapper.class),
			filter(false, true, false));

		List<Long> ids = flatten(controller.tree(null, null).getData());

		assertThat(ids).contains(1L, 2L, 3L, 20L, 21L, 40L)
			.doesNotContain(10L, 11L, 12L, 30L, 31L, 32L);
	}

	@Test
	@DisplayName("菜单管理树带名称过滤：父链被滤掉的模块按钮也不能被「提升为根」漏出来")
	void menuTreeWithNameFilterDoesNotLeakOrphans() {
		SysMenuMapper menuMapper = mock(SysMenuMapper.class);
		// 第一次：按名称过滤的结果只剩两个按钮；第二次：全量（用于父链闭包）
		when(menuMapper.selectListByQuery(any(QueryWrapper.class)))
			.thenAnswer(inv -> sample().stream().filter(m -> m.getPath() == null).toList())
			.thenAnswer(inv -> sample());
		SysMenuController controller = new SysMenuController(menuMapper, mock(SysRoleMenuMapper.class),
			filter(false, true, false));

		List<Long> ids = flatten(controller.tree("按钮", null).getData());

		assertThat(ids).containsExactly(3L);
	}

	@Test
	@DisplayName("模块全开：菜单管理树与改动前一致")
	void menuTreeUnchangedWhenAllEnabled() {
		SysMenuMapper menuMapper = mock(SysMenuMapper.class);
		when(menuMapper.selectListByQuery(any(QueryWrapper.class))).thenAnswer(inv -> sample());
		SysMenuController controller = new SysMenuController(menuMapper, mock(SysRoleMenuMapper.class),
			filter(true, true, true));

		assertThat(flatten(controller.tree(null, null).getData())).hasSize(12);
	}

	@Test
	@DisplayName("角色授权：树里看不到的已关闭模块菜单，其既有授权在重新授权后保留")
	void grantKeepsHiddenModuleGrants() {
		SysRoleMapper roleMapper = mock(SysRoleMapper.class);
		when(roleMapper.selectOneById(7L)).thenReturn(new SysRole());
		SysRoleMenuMapper roleMenuMapper = mock(SysRoleMenuMapper.class);
		when(roleMenuMapper.selectListByQuery(any(QueryWrapper.class))).thenReturn(List.of(
			roleMenu(7L, 2L), roleMenu(7L, 30L), roleMenu(7L, 31L)));
		SysMenuMapper menuMapper = mock(SysMenuMapper.class);
		when(menuMapper.selectListByQuery(any(QueryWrapper.class))).thenAnswer(inv -> sample());
		SysRoleController controller = new SysRoleController(roleMapper, roleMenuMapper,
			mock(SysRoleDeptMapper.class), mock(SysUserRoleMapper.class), menuMapper, filter(true, true, false));

		// 用户在树里取消了 /system/user（2），勾选了 /gis/layer（11）
		controller.grant(new GrantParam(7L, List.of(11L)));

		ArgumentCaptor<SysRoleMenu> inserted = ArgumentCaptor.forClass(SysRoleMenu.class);
		verify(roleMenuMapper, atLeastOnce()).insert(inserted.capture());
		assertThat(inserted.getAllValues()).extracting(SysRoleMenu::getMenuId)
			.containsExactlyInAnyOrder(30L, 31L, 11L);
	}

	@Test
	@DisplayName("角色授权：模块全开时完全以提交的勾选为准")
	void grantReplacesWhenNothingHidden() {
		SysRoleMapper roleMapper = mock(SysRoleMapper.class);
		when(roleMapper.selectOneById(7L)).thenReturn(new SysRole());
		SysRoleMenuMapper roleMenuMapper = mock(SysRoleMenuMapper.class);
		when(roleMenuMapper.selectListByQuery(any(QueryWrapper.class))).thenReturn(List.of(roleMenu(7L, 30L)));
		SysMenuMapper menuMapper = mock(SysMenuMapper.class);
		when(menuMapper.selectListByQuery(any(QueryWrapper.class))).thenAnswer(inv -> sample());
		SysRoleController controller = new SysRoleController(roleMapper, roleMenuMapper,
			mock(SysRoleDeptMapper.class), mock(SysUserRoleMapper.class), menuMapper, filter(true, true, true));

		controller.grant(new GrantParam(7L, List.of(2L)));

		ArgumentCaptor<SysRoleMenu> inserted = ArgumentCaptor.forClass(SysRoleMenu.class);
		verify(roleMenuMapper).insert(inserted.capture());
		assertThat(inserted.getAllValues()).extracting(SysRoleMenu::getMenuId).containsExactly(2L);
	}

	private static SysRoleMenu roleMenu(Long roleId, Long menuId) {
		SysRoleMenu rm = new SysRoleMenu();
		rm.setRoleId(roleId);
		rm.setMenuId(menuId);
		return rm;
	}
}
