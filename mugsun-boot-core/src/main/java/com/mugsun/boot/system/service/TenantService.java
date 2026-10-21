package com.mugsun.boot.system.service;

import com.mugsun.boot.common.constant.RoleConstants;
import com.mugsun.boot.common.constant.UserConstants;
import com.mugsun.boot.datasource.TenantDatasourceService;
import com.mugsun.boot.system.entity.SysDept;
import com.mugsun.boot.system.entity.SysPost;
import com.mugsun.boot.system.entity.SysRole;
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
import com.mugsun.boot.tenant.TenantPackageService;
import com.mugsun.core.tool.exception.ServiceException;
import com.mybatisflex.core.logicdelete.LogicDeleteManager;
import com.mybatisflex.core.query.QueryWrapper;
import com.mugsun.boot.tenant.TenantContext;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.security.SecureRandom;
import java.util.random.RandomGenerator;

/**
 * 租户服务：创建租户并一键初始化其默认部门/岗位/角色/管理员。
 */
@Service
public class TenantService {

	private final SysTenantMapper tenantMapper;
	private final SysDeptMapper deptMapper;
	private final SysPostMapper postMapper;
	private final SysRoleMapper roleMapper;
	private final SysUserMapper userMapper;
	private final SysUserRoleMapper userRoleMapper;
	private final SysRoleMenuMapper roleMenuMapper;
	private final SysRoleDeptMapper roleDeptMapper;
	private final TenantDatasourceService datasourceService;
	private final PasswordEncoder passwordEncoder;
	private final com.mugsun.boot.tenant.TenantValidator tenantValidator;
	private final com.mugsun.boot.security.SecurityPolicyService securityPolicyService;
	private final TenantPackageService packageService;
	/** 候选编号的随机源，构造注入（测试用固定序列验证已删除编号不会被复用） */
	private final RandomGenerator random;
	/**
	 * 建租户每次尝试一个独立事务（REQUIRES_NEW）：撞键后只回滚这次尝试，换号重来（PG 系撞键后事务不能继续用）；
	 * 外层即使已有事务，也不会被撞键标记为只能回滚。txManager 为 null（单测）时直接执行。
	 */
	private final TransactionTemplate txTemplate;

	/** genCode 最多抽几次候选编号 */
	static final int GEN_CODE_MAX_ATTEMPTS = 10;
	/** 租户行插入撞唯一键（并发建租户抽到同一编号）后，换号重试的次数 */
	static final int INSERT_DUPLICATE_RETRIES = 2;

	@org.springframework.beans.factory.annotation.Autowired
	public TenantService(SysTenantMapper tenantMapper, SysDeptMapper deptMapper, SysPostMapper postMapper,
						 SysRoleMapper roleMapper, SysUserMapper userMapper, SysUserRoleMapper userRoleMapper,
						 SysRoleMenuMapper roleMenuMapper, SysRoleDeptMapper roleDeptMapper,
						 TenantDatasourceService datasourceService,
						 PasswordEncoder passwordEncoder, com.mugsun.boot.tenant.TenantValidator tenantValidator,
						 com.mugsun.boot.security.SecurityPolicyService securityPolicyService,
						 TenantPackageService packageService, PlatformTransactionManager txManager) {
		this(tenantMapper, deptMapper, postMapper, roleMapper, userMapper, userRoleMapper, roleMenuMapper, roleDeptMapper,
			datasourceService, passwordEncoder, tenantValidator, securityPolicyService, packageService, new SecureRandom(),
			txManager);
	}

	public TenantService(SysTenantMapper tenantMapper, SysDeptMapper deptMapper, SysPostMapper postMapper,
						 SysRoleMapper roleMapper, SysUserMapper userMapper, SysUserRoleMapper userRoleMapper,
						 SysRoleMenuMapper roleMenuMapper, SysRoleDeptMapper roleDeptMapper,
						 TenantDatasourceService datasourceService,
						 PasswordEncoder passwordEncoder, com.mugsun.boot.tenant.TenantValidator tenantValidator,
						 com.mugsun.boot.security.SecurityPolicyService securityPolicyService,
						 TenantPackageService packageService, RandomGenerator random, PlatformTransactionManager txManager) {
		this.random = random;
		this.txTemplate = newAttemptTemplate(txManager);
		this.tenantMapper = tenantMapper;
		this.deptMapper = deptMapper;
		this.postMapper = postMapper;
		this.roleMapper = roleMapper;
		this.userMapper = userMapper;
		this.userRoleMapper = userRoleMapper;
		this.roleMenuMapper = roleMenuMapper;
		this.roleDeptMapper = roleDeptMapper;
		this.datasourceService = datasourceService;
		this.passwordEncoder = passwordEncoder;
		this.tenantValidator = tenantValidator;
		this.securityPolicyService = securityPolicyService;
		this.packageService = packageService;
	}

	/** 创建租户并初始化默认数据，返回租户编号 */
	public String create(SysTenant tenant) {
		if (tenant == null) {
			throw new ServiceException("请填写租户名称后再创建");
		}
		normalize(tenant, false);
		// 验证码开关只能由平台超管经 /system/tenant/captcha 关闭，创建时一律开启
		tenant.setCaptchaEnabled(1);
		for (int attempt = 0; ; attempt++) {
			boolean[] tenantRowDuplicate = {false};
			try {
				return inTransaction(() -> {
					String code = genCode();
					tenant.setTenantCode(code);
					try {
						tenantMapper.insertSelective(tenant);
					} catch (DuplicateKeyException e) {
						tenantRowDuplicate[0] = true;
						throw e;
					}
					// 忽略当前会话租户条件，按新租户编号写入默认数据
					TenantContext.ignore(() -> initDefaults(code, tenant.getTenantName()));
					return code;
				});
			} catch (DuplicateKeyException e) {
				// 只对租户行撞键（并发建租户抽到同一编号）换号重试；默认数据撞键照常抛出
				tenant.setId(null);
				tenant.setTenantCode(null);
				if (!tenantRowDuplicate[0]) {
					throw e;
				}
				if (attempt >= INSERT_DUPLICATE_RETRIES) {
					throw new ServiceException("同时创建的租户较多，请稍后重试");
				}
			}
		}
	}

	private static TransactionTemplate newAttemptTemplate(PlatformTransactionManager txManager) {
		if (txManager == null) {
			return null;
		}
		TransactionTemplate t = new TransactionTemplate(txManager);
		t.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
		return t;
	}

	private <T> T inTransaction(java.util.function.Supplier<T> action) {
		return txTemplate == null ? action.get() : txTemplate.execute(status -> action.get());
	}

	/**
	 * 更新租户信息（含状态/期限/配额/套餐分配），不重新初始化默认数据；更新后清缓存使停用/过期/换套餐即时生效。
	 * 用 UpdateEntity 显式追踪字段，支持将 package_id 置空以解绑套餐（Flex update(entity) 默认忽略 null）。
	 */
	public void update(SysTenant tenant) {
		if (tenant == null || tenant.getId() == null) {
			throw new ServiceException("这个租户不存在。请刷新列表后再编辑");
		}
		SysTenant exist = tenantMapper.selectOneById(tenant.getId());
		if (exist == null) {
			throw new ServiceException("这个租户不存在。请刷新列表后再编辑");
		}
		normalize(tenant, true);
		if ("000000".equals(exist.getTenantCode()) && Integer.valueOf(0).equals(tenant.getStatus())) {
			throw new ServiceException("平台租户不能停用。停用后所有人都会无法登录，请保持为正常");
		}
		SysTenant upd = com.mybatisflex.core.util.UpdateEntity.of(SysTenant.class, tenant.getId());
		upd.setTenantName(tenant.getTenantName());
		upd.setContactUser(tenant.getContactUser());
		upd.setContactPhone(tenant.getContactPhone());
		upd.setExpireTime(tenant.getExpireTime());
		upd.setPackageId(tenant.getPackageId());
		upd.setStatus(tenant.getStatus());
		upd.setAccountCount(tenant.getAccountCount());
		tenantMapper.update(upd);
		if (exist != null) {
			tenantValidator.evict(exist.getTenantCode());
		}
	}

	/** 按套餐 id 查绑定租户（套餐删除前占用检查）。 */
	/** 租户验证码开关（调用方负责平台超管鉴权与审计），返回租户编号 */
	public String setCaptchaEnabled(Long id, boolean enabled) {
		return setCaptcha(id, enabled, null);
	}

	/** setCaptcha 的 types 取此值表示清回 NULL（平台默认） */
	public static final String CLEAR_CAPTCHA_TYPES = "";

	/** 租户验证码开关 + 启用题型（types 为 null 不改，"" 清回 NULL；调用方负责鉴权、审计与取值校验） */
	public String setCaptcha(Long id, boolean enabled, String types) {
		SysTenant exist = id == null ? null : tenantMapper.selectOneById(id);
		if (exist == null) {
			throw new ServiceException("这个租户不存在。请刷新列表后再编辑");
		}
		SysTenant upd = com.mybatisflex.core.util.UpdateEntity.of(SysTenant.class, id);
		upd.setCaptchaEnabled(enabled ? 1 : 0);
		if (types != null) {
			upd.setCaptchaTypes(types.isEmpty() ? null : types);
		}
		tenantMapper.update(upd);
		tenantValidator.evict(exist.getTenantCode());
		return exist.getTenantCode();
	}

	public List<SysTenant> listByPackageIds(java.util.Collection<Long> packageIds) {
		if (packageIds == null || packageIds.isEmpty()) {
			return List.of();
		}
		return tenantMapper.selectListByQuery(QueryWrapper.create().in("package_id", packageIds));
	}

	/**
	 * 逻辑删除租户并清缓存，避免被删租户在 TTL 内仍缓存有效。返回实际删除条数。
	 * 租户和它的组织数据（见 {@link #purgeTenantData(String)}）在同一个事务里逻辑删除，任何一步失败整体回滚。
	 */
	@Transactional(rollbackFor = Exception.class)
	public int remove(List<Long> ids) {
		if (ids == null || ids.isEmpty()) {
			throw new ServiceException("请先选择要删除的租户");
		}
		LinkedHashSet<Long> distinct = new LinkedHashSet<>();
		for (Long id : ids) {
			if (id == null) {
				throw new ServiceException("有租户不存在。请刷新列表后再删除");
			}
			distinct.add(id);
		}
		List<Long> idList = new ArrayList<>(distinct);
		List<SysTenant> tenants = tenantMapper.selectListByIds(idList);
		if (tenants.size() != idList.size()) {
			throw new ServiceException("有租户不存在，或已经被删除。请刷新列表后再试");
		}
		for (SysTenant tenant : tenants) {
			if ("000000".equals(tenant.getTenantCode())) {
				String name = tenant.getTenantName() == null || tenant.getTenantName().isBlank()
					? "平台租户" : tenant.getTenantName();
				throw new ServiceException("「" + name + "」是平台租户，不能删除。请改其他租户");
			}
		}
		tenantMapper.deleteBatchByIds(idList);
		for (SysTenant tenant : tenants) {
			tenantValidator.evict(tenant.getTenantCode());
			purgeTenantData(tenant.getTenantCode());
		}
		unregisterDatasourcesAfterCommit(tenants);
		return tenants.size();
	}

	private void normalize(SysTenant tenant, boolean updating) {
		String name = tenant.getTenantName() == null ? "" : tenant.getTenantName().trim();
		if (name.isEmpty()) {
			throw new ServiceException("请填写租户名称");
		}
		if (name.length() > 64) {
			throw new ServiceException("租户名称请控制在 64 个字以内");
		}
		tenant.setTenantName(name);
		String contact = tenant.getContactUser() == null ? "" : tenant.getContactUser().trim();
		if (contact.length() > 32) {
			throw new ServiceException("联系人请控制在 32 个字以内");
		}
		tenant.setContactUser(contact.isEmpty() ? null : contact);
		String phone = tenant.getContactPhone() == null ? "" : tenant.getContactPhone().trim();
		if (!phone.isEmpty() && !phone.matches("^1\\d{10}$")) {
			throw new ServiceException("联系电话请填写 11 位手机号。不需要联系电话可以留空");
		}
		tenant.setContactPhone(phone.isEmpty() ? null : phone);
		if (tenant.getStatus() == null) {
			tenant.setStatus(1);
		} else if (tenant.getStatus() != 0 && tenant.getStatus() != 1) {
			throw new ServiceException("状态只能选择正常或停用");
		}
		if (tenant.getAccountCount() == null) {
			tenant.setAccountCount(-1);
		} else if (tenant.getAccountCount() < -1) {
			throw new ServiceException("账号上限请填 -1（不限制）或大于等于 0 的整数");
		}
		if (tenant.getPackageId() != null && packageService.getById(tenant.getPackageId()) == null) {
			throw new ServiceException(updating
				? "这个套餐不存在。请重新选择套餐，或清空套餐表示不限功能"
				: "这个套餐不存在。请重新选择套餐，或先不选套餐");
		}
	}

	/**
	 * 逻辑删除租户的组织数据：用户及其用户-角色关联、部门、岗位、角色及其角色-菜单 / 角色-部门关联、租户独立数据源配置。
	 * 用户的岗位是 sys_user.post_id 列，没有单独的用户-岗位关联表，随用户一起删。
	 * 这些表的实体都继承 BaseEntity（is_deleted 逻辑删除），Mapper 的 delete 会生成 UPDATE ... SET is_deleted = 1。
	 * 平台租户不在这里清。跨租户清理必须忽略当前会话的租户条件。
	 */
	private void purgeTenantData(String code) {
		if (code == null || code.isBlank() || "000000".equals(code)) {
			return;
		}
		TenantContext.ignore(() -> purgeTenantDataUnchecked(code));
	}

	private void purgeTenantDataUnchecked(String code) {
		java.util.List<SysUser> users = userMapper.selectListByQuery(QueryWrapper.create().eq("tenant_id", code));
		java.util.List<Long> userIds = new java.util.ArrayList<>();
		for (SysUser user : users) {
			if (user.getId() != null) {
				userIds.add(user.getId());
			}
		}
		if (!userIds.isEmpty()) {
			userRoleMapper.deleteByQuery(QueryWrapper.create().in("user_id", userIds));
		}
		userMapper.deleteByQuery(QueryWrapper.create().eq("tenant_id", code));
		deptMapper.deleteByQuery(QueryWrapper.create().eq("tenant_id", code));
		postMapper.deleteByQuery(QueryWrapper.create().eq("tenant_id", code));
		java.util.List<Long> roleIds = new java.util.ArrayList<>();
		for (SysRole role : roleMapper.selectListByQuery(QueryWrapper.create().eq("tenant_id", code))) {
			if (role.getId() != null) {
				roleIds.add(role.getId());
			}
		}
		if (!roleIds.isEmpty()) {
			roleMenuMapper.deleteByQuery(QueryWrapper.create().in("role_id", roleIds));
			roleDeptMapper.deleteByQuery(QueryWrapper.create().in("role_id", roleIds));
		}
		roleMapper.deleteByQuery(QueryWrapper.create().eq("tenant_id", code));
		datasourceService.removeByTenantCode(code);
	}

	/** 已注册的租户独立数据源在事务提交后注销（回滚时保持原样），与「删除数据源」接口的效果一致 */
	private void unregisterDatasourcesAfterCommit(List<SysTenant> tenants) {
		List<String> codes = new ArrayList<>();
		for (SysTenant tenant : tenants) {
			if (tenant.getTenantCode() != null && !"000000".equals(tenant.getTenantCode())) {
				codes.add(tenant.getTenantCode());
			}
		}
		datasourceService.unregisterAfterCommit(codes);
	}

	private void initDefaults(String code, String tenantName) {
		SysDept dept = new SysDept();
		dept.setDeptName(tenantName);
		dept.setParentId(0L);
		dept.setSort(1);
		dept.setTenantId(code);
		deptMapper.insertSelective(dept);

		SysPost post = new SysPost();
		post.setPostCode("default");
		post.setPostName("默认岗位");
		post.setSort(1);
		post.setTenantId(code);
		postMapper.insertSelective(post);

		SysRole role = new SysRole();
		role.setRoleName("管理员");
		role.setRoleCode(RoleConstants.ADMIN);
		role.setSort(1);
		role.setDataScope(com.mugsun.boot.common.constant.DataScopeConstants.ALL);
		role.setTenantId(code);
		roleMapper.insertSelective(role);

		SysUser admin = new SysUser();
		admin.setUsername(UserConstants.ADMIN_USERNAME);
		admin.setPassword(passwordEncoder.encode(securityPolicyService.getInitPassword()));
		admin.setNickname(tenantName + "管理员");
		admin.setStatus(1);
		admin.setDeptId(dept.getId());
		admin.setPostId(post.getId());
		admin.setTenantId(code);
		userMapper.insertSelective(admin);

		SysUserRole userRole = new SysUserRole();
		userRole.setUserId(admin.getId());
		userRole.setRoleId(role.getId());
		userRoleMapper.insertSelective(userRole);
	}

	/**
	 * 生成 6 位租户编号，规避超管保留号 000000 且永不复用。
	 * 查重绕过逻辑删除过滤，已删除租户的编号也算占用：级联只清组织数据，业务表、凭据、会话和数据源路由仍按编号关联，
	 * 复用编号会让新租户接手旧租户的残留数据。
	 */
	private String genCode() {
		for (int i = 0; i < GEN_CODE_MAX_ATTEMPTS; i++) {
			String code = String.valueOf(100000 + random.nextInt(900000));
			if (!tenantCodeTaken(code)) {
				return code;
			}
		}
		throw new ServiceException("没能生成可用的租户编号，请稍后重试");
	}


	private boolean tenantCodeTaken(String code) {
		Long n = LogicDeleteManager.execWithoutLogicDelete(
			() -> tenantMapper.selectCountByQuery(QueryWrapper.create().eq("tenant_code", code)));
		return n != null && n > 0;
	}
}
