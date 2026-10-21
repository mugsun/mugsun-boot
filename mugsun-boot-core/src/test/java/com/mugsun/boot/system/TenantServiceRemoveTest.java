package com.mugsun.boot.system;

import com.mugsun.boot.datasource.TenantDataSourceRegistry;
import com.mugsun.boot.datasource.TenantDatasourceService;
import com.mugsun.boot.datasource.entity.SysTenantDatasource;
import com.mugsun.boot.datasource.mapper.SysTenantDatasourceMapper;
import com.mugsun.boot.system.entity.SysDept;
import com.mugsun.boot.system.entity.SysPost;
import com.mugsun.boot.system.entity.SysRole;
import com.mugsun.boot.system.entity.SysRoleDept;
import com.mugsun.boot.system.entity.SysRoleMenu;
import com.mugsun.boot.system.entity.SysTenant;
import com.mugsun.boot.system.entity.SysUser;
import com.mugsun.boot.system.entity.SysUserRole;
import com.mugsun.boot.system.mapper.SysDeptMapper;
import com.mugsun.boot.system.mapper.SysPostMapper;
import com.mugsun.boot.system.mapper.SysRoleDeptMapper;
import com.mugsun.boot.system.mapper.SysRoleMapper;
import com.mugsun.boot.system.mapper.SysRoleMenuMapper;
import com.mugsun.boot.system.mapper.SysTenantMapper;
import com.mugsun.boot.system.mapper.SysUserMapper;
import com.mugsun.boot.system.mapper.SysUserRoleMapper;
import com.mugsun.boot.system.service.TenantService;
import com.mugsun.core.mybatis.base.BaseEntity;
import com.mugsun.core.tool.exception.ServiceException;
import com.mybatisflex.annotation.Column;
import com.mybatisflex.core.query.QueryWrapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.List;
import java.util.Random;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 删除租户的级联（Mockito，不启 Spring 容器）：组织数据与关联、租户数据源一起逻辑删除，同一事务。
 * 租户数据源经数据源域的 {@link TenantDatasourceService} 删除（真实实例 + mock Mapper），不跨域直连 Mapper。
 */
class TenantServiceRemoveTest {

	private SysTenantMapper tenantMapper;
	private SysUserMapper userMapper;
	private SysUserRoleMapper userRoleMapper;
	private SysDeptMapper deptMapper;
	private SysPostMapper postMapper;
	private SysRoleMapper roleMapper;
	private SysRoleMenuMapper roleMenuMapper;
	private SysRoleDeptMapper roleDeptMapper;
	private SysTenantDatasourceMapper datasourceMapper;
	private TenantDataSourceRegistry registry;
	private TenantService service;

	@BeforeEach
	@SuppressWarnings("unchecked")
	void setUp() {
		tenantMapper = mock(SysTenantMapper.class);
		userMapper = mock(SysUserMapper.class);
		userRoleMapper = mock(SysUserRoleMapper.class);
		deptMapper = mock(SysDeptMapper.class);
		postMapper = mock(SysPostMapper.class);
		roleMapper = mock(SysRoleMapper.class);
		roleMenuMapper = mock(SysRoleMenuMapper.class);
		roleDeptMapper = mock(SysRoleDeptMapper.class);
		datasourceMapper = mock(SysTenantDatasourceMapper.class);
		registry = mock(TenantDataSourceRegistry.class);
		ObjectProvider<TenantDataSourceRegistry> provider = mock(ObjectProvider.class);
		when(provider.getIfAvailable()).thenReturn(registry);
		service = new TenantService(tenantMapper, deptMapper, postMapper, roleMapper, userMapper, userRoleMapper,
			roleMenuMapper, roleDeptMapper, new TenantDatasourceService(datasourceMapper, provider),
			null, mock(com.mugsun.boot.tenant.TenantValidator.class), null, null);
	}

	private static SysTenant tenant(long id, String code) {
		SysTenant t = new SysTenant();
		t.setId(id);
		t.setTenantCode(code);
		t.setTenantName("T" + code);
		return t;
	}

	private static SysRole role(long id) {
		SysRole r = new SysRole();
		r.setId(id);
		return r;
	}

	private static SysUser user(long id) {
		SysUser u = new SysUser();
		u.setId(id);
		return u;
	}

	private static String sql(QueryWrapper qw) {
		return qw.toSQL().replace("`", "").replace("\"", "");
	}

	@Test
	@DisplayName("删租户：角色-菜单、角色-部门按该租户角色删，租户数据源按租户编号删，原有的用户/部门/岗位/角色照删")
	void cascadesToLinksAndDatasource() {
		when(tenantMapper.selectListByIds(anyList())).thenReturn(List.of(tenant(1L, "700001")));
		when(userMapper.selectListByQuery(any(QueryWrapper.class))).thenReturn(List.of(user(11L), user(12L)));
		when(roleMapper.selectListByQuery(any(QueryWrapper.class))).thenReturn(List.of(role(21L), role(22L)));

		assertThat(service.remove(List.of(1L))).isEqualTo(1);

		ArgumentCaptor<QueryWrapper> rm = ArgumentCaptor.forClass(QueryWrapper.class);
		verify(roleMenuMapper).deleteByQuery(rm.capture());
		assertThat(sql(rm.getValue())).contains("role_id IN (21, 22)");
		ArgumentCaptor<QueryWrapper> rd = ArgumentCaptor.forClass(QueryWrapper.class);
		verify(roleDeptMapper).deleteByQuery(rd.capture());
		assertThat(sql(rd.getValue())).contains("role_id IN (21, 22)");
		ArgumentCaptor<QueryWrapper> ds = ArgumentCaptor.forClass(QueryWrapper.class);
		verify(datasourceMapper).deleteByQuery(ds.capture());
		assertThat(sql(ds.getValue())).contains("tenant_code = '700001'");
		ArgumentCaptor<QueryWrapper> ur = ArgumentCaptor.forClass(QueryWrapper.class);
		verify(userRoleMapper).deleteByQuery(ur.capture());
		assertThat(sql(ur.getValue())).contains("user_id IN (11, 12)");
		verify(userMapper).deleteByQuery(any(QueryWrapper.class));
		verify(deptMapper).deleteByQuery(any(QueryWrapper.class));
		verify(postMapper).deleteByQuery(any(QueryWrapper.class));
		verify(roleMapper).deleteByQuery(any(QueryWrapper.class));
		verify(tenantMapper).deleteBatchByIds(List.of(1L));
		// 没有事务同步（单测直接调用）时立即注销已注册的独立数据源
		verify(registry).unregister("700001");
	}

	@Test
	@DisplayName("租户没有角色时不发 role_id IN () 的空条件删除")
	void noRolesSkipsLinkDeletes() {
		when(tenantMapper.selectListByIds(anyList())).thenReturn(List.of(tenant(1L, "700001")));

		service.remove(List.of(1L));

		verify(roleMenuMapper, never()).deleteByQuery(any(QueryWrapper.class));
		verify(roleDeptMapper, never()).deleteByQuery(any(QueryWrapper.class));
		verify(datasourceMapper).deleteByQuery(any(QueryWrapper.class));
	}

	@Test
	@DisplayName("事务中：独立数据源等提交后才注销，回滚不注销")
	void unregisterWaitsForCommit() {
		when(tenantMapper.selectListByIds(anyList())).thenReturn(List.of(tenant(1L, "700001")));
		TransactionSynchronizationManager.initSynchronization();
		try {
			service.remove(List.of(1L));
			verify(registry, never()).unregister(any());
			List<TransactionSynchronization> syncs = TransactionSynchronizationManager.getSynchronizations();
			assertThat(syncs).hasSize(1);
			syncs.forEach(s -> s.afterCompletion(TransactionSynchronization.STATUS_ROLLED_BACK));
			verify(registry, never()).unregister(any());
			syncs.forEach(TransactionSynchronization::afterCommit);
			verify(registry).unregister("700001");
		} finally {
			TransactionSynchronizationManager.clearSynchronization();
		}
	}

	@Test
	@DisplayName("平台租户不能删，什么都不动")
	void platformTenantRefused() {
		when(tenantMapper.selectListByIds(anyList())).thenReturn(List.of(tenant(1L, "000000")));

		assertThatThrownBy(() -> service.remove(List.of(1L))).isInstanceOf(ServiceException.class);
		verify(tenantMapper, never()).deleteBatchByIds(anyList());
		verifyNoInteractions(roleMenuMapper, roleDeptMapper, datasourceMapper, registry);
	}

	@Test
	@DisplayName("remove 带 @Transactional(rollbackFor = Exception.class)：租户和组织数据同一事务")
	void removeIsTransactional() throws Exception {
		Transactional tx = TenantService.class.getMethod("remove", List.class).getAnnotation(Transactional.class);
		assertThat(tx).isNotNull();
		assertThat(tx.rollbackFor()).contains(Exception.class);
	}

	@Test
	@DisplayName("级联涉及的实体都走逻辑删除（继承 BaseEntity，is_deleted 标了 isLogicDelete）")
	void allCascadedEntitiesUseLogicalDelete() throws Exception {
		assertThat(BaseEntity.class.getDeclaredField("isDeleted").getAnnotation(Column.class).isLogicDelete()).isTrue();
		for (Class<?> c : List.of(SysTenant.class, SysUser.class, SysUserRole.class, SysDept.class, SysPost.class,
			SysRole.class, SysRoleMenu.class, SysRoleDept.class, SysTenantDatasource.class)) {
			assertThat(BaseEntity.class).as(c.getSimpleName()).isAssignableFrom(c);
		}
	}

	/** 依次给出固定候选：nextInt(900000) 返回 code - 100000 */
	private static Random sequence(int... codes) {
		return new Random() {
			private int i;

			@Override
			public int nextInt(int bound) {
				return codes[i++] - 100000;
			}
		};
	}

	@Test
	@DisplayName("建租户编号：查重绕过逻辑删除，已删除租户的编号不会被复用")
	void genCodeSkipsCodesOfDeletedTenants() throws Exception {
		// 700001 是已删除租户：只有不带逻辑删除过滤的查询才看得到它
		when(tenantMapper.selectCountByQuery(any(QueryWrapper.class))).thenAnswer(inv -> {
			boolean seesDeleted = com.mybatisflex.core.logicdelete.LogicDeleteManager.getLogicDeleteColumn("is_deleted") == null;
			return sql(inv.getArgument(0)).contains("'700001'") && seesDeleted ? 1L : 0L;
		});
		org.springframework.test.util.ReflectionTestUtils.setField(service, "random", sequence(700001, 700002));
		java.lang.reflect.Method genCode = TenantService.class.getDeclaredMethod("genCode");
		genCode.setAccessible(true);

		assertThat(genCode.invoke(service)).isEqualTo("700002");
		// 查完恢复逻辑删除过滤，不影响同线程后续查询
		assertThat(com.mybatisflex.core.logicdelete.LogicDeleteManager.getLogicDeleteColumn("is_deleted")).isEqualTo("is_deleted");
	}
}
