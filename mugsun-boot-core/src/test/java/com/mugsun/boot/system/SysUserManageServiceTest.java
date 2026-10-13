package com.mugsun.boot.system;

import com.mugsun.boot.system.entity.SysUser;
import com.mugsun.boot.system.entity.SysUserRole;
import com.mugsun.boot.system.mapper.SysRoleMapper;
import com.mugsun.boot.system.mapper.SysUserMapper;
import com.mugsun.boot.system.mapper.SysUserRoleMapper;
import com.mugsun.boot.system.service.SysUserManageService;
import com.mugsun.core.tool.exception.ServiceException;
import com.mybatisflex.core.query.QueryWrapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;

import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/** SysUserManageService 纯单测（Mockito，不启 Spring 容器） */
class SysUserManageServiceTest {

	private SysUserMapper userMapper;
	private SysUserRoleMapper userRoleMapper;
	private SysRoleMapper roleMapper;
	private SysUserManageService service;

	@BeforeEach
	void setUp() {
		userMapper = mock(SysUserMapper.class);
		userRoleMapper = mock(SysUserRoleMapper.class);
		roleMapper = mock(SysRoleMapper.class);
		service = new SysUserManageService(userMapper, userRoleMapper, roleMapper, null, null, null, null, null);
	}

	@Test
	void syncUserRoles_nullRoleIds_isNoop() {
		service.syncUserRoles(1L, null);
		verifyNoInteractions(roleMapper, userRoleMapper);
	}

	@Test
	void syncUserRoles_duplicatesValidatedByUniqueSizeAndInsertedOnceInOrder() {
		when(roleMapper.selectCountByQuery(any(QueryWrapper.class))).thenReturn(2L);
		service.syncUserRoles(7L, Arrays.asList(3L, 2L, 3L, 2L));
		ArgumentCaptor<SysUserRole> cap = ArgumentCaptor.forClass(SysUserRole.class);
		verify(userRoleMapper).deleteByQuery(any(QueryWrapper.class));
		verify(userRoleMapper, times(2)).insert(cap.capture());
		assertEquals(List.of(3L, 2L), cap.getAllValues().stream().map(SysUserRole::getRoleId).toList());
		assertTrue(cap.getAllValues().stream().allMatch(r -> r.getUserId().equals(7L)));
	}

	@Test
	void syncUserRoles_invalidRole_throwsAndWritesNothing() {
		when(roleMapper.selectCountByQuery(any(QueryWrapper.class))).thenReturn(1L);
		assertThrows(ServiceException.class, () -> service.syncUserRoles(7L, List.of(1L, 99L)));
		verify(userRoleMapper, never()).deleteByQuery(any(QueryWrapper.class));
		verify(userRoleMapper, never()).insert(any(SysUserRole.class));
	}

	@Test
	void syncUserRoles_emptyList_clearsRolesWithoutCount() {
		service.syncUserRoles(7L, List.of());
		verify(roleMapper, never()).selectCountByQuery(any(QueryWrapper.class));
		verify(userRoleMapper).deleteByQuery(any(QueryWrapper.class));
		verify(userRoleMapper, never()).insert(any(SysUserRole.class));
	}

	@Test
	void removeUsers_clearsRolesBeforeDelete() {
		SysUser u = new SysUser();
		u.setId(5L);
		try (var stp = mockStatic(cn.dev33.satoken.stp.StpUtil.class)) {
			service.removeUsers(List.of(u));
			InOrder order = inOrder(userRoleMapper, userMapper);
			order.verify(userRoleMapper).deleteByQuery(any(QueryWrapper.class));
			order.verify(userMapper).deleteById(5L);
			stp.verify(() -> cn.dev33.satoken.stp.StpUtil.kickout(5L));
		}
	}
}
