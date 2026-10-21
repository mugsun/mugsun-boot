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
			null, mock(com.mugsun.boot.tenant.TenantValidator.class), null, null, new java.util.Random(), null);
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
	private static java.util.random.RandomGenerator sequence(int... codes) {
		int[] i = {0};
		return new java.util.random.RandomGenerator() {
			@Override
			public long nextLong() {
				throw new UnsupportedOperationException();
			}

			@Override
			public int nextInt(int bound) {
				return codes[Math.min(i[0]++, codes.length - 1)] - 100000;
			}
		};
	}

	private TenantService serviceWith(java.util.random.RandomGenerator random) {
		return serviceWith(random, null);
	}

	@SuppressWarnings("unchecked")
	private TenantService serviceWith(java.util.random.RandomGenerator random,
									  org.springframework.transaction.PlatformTransactionManager tm) {
		ObjectProvider<TenantDataSourceRegistry> provider = mock(ObjectProvider.class);
		return new TenantService(tenantMapper, deptMapper, postMapper, roleMapper, userMapper, userRoleMapper,
			roleMenuMapper, roleDeptMapper, new TenantDatasourceService(datasourceMapper, provider),
			null, mock(com.mugsun.boot.tenant.TenantValidator.class), null, null, random, tm);
	}

	/** 内存事务管理器：记录开启 / 挂起 / 提交 / 回滚，栈顶是当前事务，支持 REQUIRES_NEW 挂起外层 */
	static final class RecordingTxManager extends org.springframework.transaction.support.AbstractPlatformTransactionManager {
		final java.util.Deque<Object> active = new java.util.ArrayDeque<>();
		final List<Integer> propagations = new java.util.ArrayList<>();
		int begins;
		int suspends;
		int commits;
		int rollbacks;

		@Override
		protected Object doGetTransaction() {
			return new Object[]{active.peek()};
		}

		@Override
		protected boolean isExistingTransaction(Object tx) {
			return ((Object[]) tx)[0] != null;
		}

		@Override
		protected void doBegin(Object tx, org.springframework.transaction.TransactionDefinition def) {
			Object token = new Object();
			((Object[]) tx)[0] = token;
			active.push(token);
			propagations.add(def.getPropagationBehavior());
			begins++;
		}

		@Override
		protected Object doSuspend(Object tx) {
			suspends++;
			((Object[]) tx)[0] = null;
			return active.pop();
		}

		@Override
		protected void doResume(Object tx, Object suspended) {
			active.push(suspended);
		}

		@Override
		protected void doCommit(org.springframework.transaction.support.DefaultTransactionStatus status) {
			commits++;
			active.pop();
		}

		@Override
		protected void doRollback(org.springframework.transaction.support.DefaultTransactionStatus status) {
			rollbacks++;
			active.pop();
		}
	}

	private static Object invoke(Object target, String name, Class<?>[] types, Object... args) throws Throwable {
		java.lang.reflect.Method m = TenantService.class.getDeclaredMethod(name, types);
		m.setAccessible(true);
		try {
			return m.invoke(target, args);
		} catch (java.lang.reflect.InvocationTargetException e) {
			throw e.getCause();
		}
	}

	@Test
	@DisplayName("建租户编号：查重绕过逻辑删除，已删除租户的编号不会被复用")
	void genCodeSkipsCodesOfDeletedTenants() throws Throwable {
		// 700001 是已删除租户：只有不带逻辑删除过滤的查询才看得到它
		when(tenantMapper.selectCountByQuery(any(QueryWrapper.class))).thenAnswer(inv -> {
			boolean seesDeleted = com.mybatisflex.core.logicdelete.LogicDeleteManager.getLogicDeleteColumn("is_deleted") == null;
			return sql(inv.getArgument(0)).contains("'700001'") && seesDeleted ? 1L : 0L;
		});
		TenantService svc = serviceWith(sequence(700001, 700002));

		assertThat(invoke(svc, "genCode", new Class<?>[0])).isEqualTo("700002");
		// 查完恢复逻辑删除过滤，不影响同线程后续查询
		assertThat(com.mybatisflex.core.logicdelete.LogicDeleteManager.getLogicDeleteColumn("is_deleted")).isEqualTo("is_deleted");
	}

	@Test
	@DisplayName("建租户编号：连续抽 10 次都被占用就报错，不会无限循环")
	void genCodeGivesUpAfterMaxAttempts() {
		when(tenantMapper.selectCountByQuery(any(QueryWrapper.class))).thenReturn(1L);
		TenantService svc = serviceWith(sequence(700001));

		assertThatThrownBy(() -> invoke(svc, "genCode", new Class<?>[0]))
			.isInstanceOf(ServiceException.class).hasMessageContaining("没能生成可用的租户编号");
		verify(tenantMapper, org.mockito.Mockito.times(10)).selectCountByQuery(any(QueryWrapper.class));
	}

	@Test
	@DisplayName("并发建租户撞键：换号重试，最多重试 2 次，仍冲突抛 DuplicateKeyException（core 返回 400 数据已存在）")
	void createRetriesOnDuplicateTenantCodeThenGivesUp() {
		when(tenantMapper.selectCountByQuery(any(QueryWrapper.class))).thenReturn(0L);
		when(tenantMapper.insertSelective(any(SysTenant.class)))
			.thenThrow(new org.springframework.dao.DuplicateKeyException("dup"))
			.thenReturn(1);
		// 租户行插入成功后进入默认数据初始化：这里用标记异常截停，只验证换号
		when(deptMapper.insertSelective(any(SysDept.class))).thenThrow(new IllegalStateException("stop-after-tenant-row"));
		TenantService svc = serviceWith(sequence(700001, 700002));
		SysTenant t = new SysTenant();
		t.setTenantName("并发");
		assertThatThrownBy(() -> svc.create(t)).hasMessage("stop-after-tenant-row");
		assertThat(t.getTenantCode()).isEqualTo("700002");
		verify(tenantMapper, org.mockito.Mockito.times(2)).insertSelective(any(SysTenant.class));

		org.mockito.Mockito.reset(tenantMapper);
		when(tenantMapper.selectCountByQuery(any(QueryWrapper.class))).thenReturn(0L);
		when(tenantMapper.insertSelective(any(SysTenant.class))).thenThrow(new org.springframework.dao.DuplicateKeyException("dup"));
		TenantService always = serviceWith(sequence(700011, 700012, 700013, 700014));
		SysTenant t2 = new SysTenant();
		t2.setTenantName("并发2");
		assertThatThrownBy(() -> always.create(t2)).isInstanceOf(ServiceException.class)
			.hasMessage("同时创建的租户较多，请稍后重试");
		verify(tenantMapper, org.mockito.Mockito.times(3)).insertSelective(any(SysTenant.class));
	}

	@Test
	@DisplayName("外层已有事务时：每次尝试 REQUIRES_NEW 独立提交/回滚，撞键换号仍生效，外层不被标记只回滚")
	void createRetryWorksInsideOuterTransaction() {
		RecordingTxManager tm = new RecordingTxManager();
		when(tenantMapper.selectCountByQuery(any(QueryWrapper.class))).thenReturn(0L);
		when(tenantMapper.insertSelective(any(SysTenant.class)))
			.thenThrow(new org.springframework.dao.DuplicateKeyException("dup"))
			.thenReturn(1);
		when(deptMapper.insertSelective(any(SysDept.class))).thenThrow(new IllegalStateException("stop-after-tenant-row"));
		TenantService svc = serviceWith(sequence(700001, 700002), tm);

		org.springframework.transaction.TransactionStatus outer =
			tm.getTransaction(new org.springframework.transaction.support.DefaultTransactionDefinition());
		Object outerToken = tm.active.peek();
		SysTenant t = new SysTenant();
		t.setTenantName("外层事务");
		assertThatThrownBy(() -> svc.create(t)).hasMessage("stop-after-tenant-row");

		assertThat(t.getTenantCode()).isEqualTo("700002");
		assertThat(tm.propagations).containsExactly(
			org.springframework.transaction.TransactionDefinition.PROPAGATION_REQUIRED,
			org.springframework.transaction.TransactionDefinition.PROPAGATION_REQUIRES_NEW,
			org.springframework.transaction.TransactionDefinition.PROPAGATION_REQUIRES_NEW);
		assertThat(tm.suspends).as("两次尝试都挂起了外层").isEqualTo(2);
		assertThat(tm.rollbacks).as("两次尝试各自回滚").isEqualTo(2);
		assertThat(tm.active).containsExactly(outerToken);
		assertThat(outer.isRollbackOnly()).isFalse();
		tm.commit(outer);
		assertThat(tm.commits).isEqualTo(1);
	}
}
