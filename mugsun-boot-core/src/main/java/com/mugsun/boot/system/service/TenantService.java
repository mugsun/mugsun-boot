package com.mugsun.boot.system.service;

import com.mugsun.boot.common.constant.RoleConstants;
import com.mugsun.boot.common.constant.UserConstants;
import com.mugsun.boot.system.entity.SysDept;
import com.mugsun.boot.system.entity.SysPost;
import com.mugsun.boot.system.entity.SysRole;
import com.mugsun.boot.system.entity.SysTenant;
import com.mugsun.boot.system.entity.SysUser;
import com.mugsun.boot.system.entity.SysUserRole;
import com.mugsun.boot.system.mapper.SysDeptMapper;
import com.mugsun.boot.system.mapper.SysPostMapper;
import com.mugsun.boot.system.mapper.SysRoleMapper;
import com.mugsun.boot.system.mapper.SysTenantMapper;
import com.mugsun.boot.system.mapper.SysUserMapper;
import com.mugsun.boot.system.mapper.SysUserRoleMapper;
import com.mybatisflex.core.query.QueryWrapper;
import com.mugsun.boot.tenant.TenantContext;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Random;

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
	private final PasswordEncoder passwordEncoder;
	private final com.mugsun.boot.tenant.TenantValidator tenantValidator;
	private final com.mugsun.boot.security.SecurityPolicyService securityPolicyService;
	private final Random random = new Random();

	public TenantService(SysTenantMapper tenantMapper, SysDeptMapper deptMapper, SysPostMapper postMapper,
						 SysRoleMapper roleMapper, SysUserMapper userMapper, SysUserRoleMapper userRoleMapper,
						 PasswordEncoder passwordEncoder, com.mugsun.boot.tenant.TenantValidator tenantValidator,
						 com.mugsun.boot.security.SecurityPolicyService securityPolicyService) {
		this.tenantMapper = tenantMapper;
		this.deptMapper = deptMapper;
		this.postMapper = postMapper;
		this.roleMapper = roleMapper;
		this.userMapper = userMapper;
		this.userRoleMapper = userRoleMapper;
		this.passwordEncoder = passwordEncoder;
		this.tenantValidator = tenantValidator;
		this.securityPolicyService = securityPolicyService;
	}

	/** 创建租户并初始化默认数据，返回租户编号 */
	@Transactional(rollbackFor = Exception.class)
	public String create(SysTenant tenant) {
		String code = genCode();
		tenant.setTenantCode(code);
		if (tenant.getStatus() == null) {
			tenant.setStatus(1);
		}
		if (tenant.getAccountCount() == null) {
			tenant.setAccountCount(-1);
		}
		tenantMapper.insertSelective(tenant);
		// 忽略当前会话租户条件，按新租户编号写入默认数据
		TenantContext.ignore(() -> initDefaults(code, tenant.getTenantName()));
		return code;
	}

	/**
	 * 更新租户信息（含状态/期限/配额/套餐分配），不重新初始化默认数据；更新后清缓存使停用/过期/换套餐即时生效。
	 * 用 UpdateEntity 显式追踪字段，支持将 package_id 置空以解绑套餐（Flex update(entity) 默认忽略 null）。
	 */
	public void update(SysTenant tenant) {
		SysTenant exist = tenantMapper.selectOneById(tenant.getId());
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

	/** 逻辑删除租户并清缓存，避免被删租户在 TTL 内仍缓存有效 */
	public void remove(java.util.List<Long> ids) {
		java.util.List<SysTenant> tenants = tenantMapper.selectListByIds(ids);
		tenantMapper.deleteBatchByIds(ids);
		tenants.forEach(t -> tenantValidator.evict(t.getTenantCode()));
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

	/** 生成 6 位租户编号，规避超管保留号 000000 且不重复 */
	private String genCode() {
		String code;
		do {
			code = String.valueOf(100000 + random.nextInt(900000));
		} while (tenantMapper.selectCountByQuery(QueryWrapper.create().eq("tenant_code", code)) > 0);
		return code;
	}
}
