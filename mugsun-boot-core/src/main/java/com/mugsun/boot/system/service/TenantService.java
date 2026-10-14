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
import com.mugsun.boot.tenant.TenantPackageService;
import com.mugsun.core.tool.exception.ServiceException;
import com.mybatisflex.core.query.QueryWrapper;
import com.mugsun.boot.tenant.TenantContext;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
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
	private final TenantPackageService packageService;
	private final Random random = new Random();

	public TenantService(SysTenantMapper tenantMapper, SysDeptMapper deptMapper, SysPostMapper postMapper,
						 SysRoleMapper roleMapper, SysUserMapper userMapper, SysUserRoleMapper userRoleMapper,
						 PasswordEncoder passwordEncoder, com.mugsun.boot.tenant.TenantValidator tenantValidator,
						 com.mugsun.boot.security.SecurityPolicyService securityPolicyService,
						 TenantPackageService packageService) {
		this.tenantMapper = tenantMapper;
		this.deptMapper = deptMapper;
		this.postMapper = postMapper;
		this.roleMapper = roleMapper;
		this.userMapper = userMapper;
		this.userRoleMapper = userRoleMapper;
		this.passwordEncoder = passwordEncoder;
		this.tenantValidator = tenantValidator;
		this.securityPolicyService = securityPolicyService;
		this.packageService = packageService;
	}

	/** 创建租户并初始化默认数据，返回租户编号 */
	@Transactional(rollbackFor = Exception.class)
	public String create(SysTenant tenant) {
		if (tenant == null) {
			throw new ServiceException("请填写租户名称后再创建");
		}
		normalize(tenant, false);
		// 验证码开关只能由平台超管经 /system/tenant/captcha 关闭，创建时一律开启
		tenant.setCaptchaEnabled(1);
		String code = genCode();
		tenant.setTenantCode(code);
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

	/** 逻辑删除租户并清缓存，避免被删租户在 TTL 内仍缓存有效。返回实际删除条数。 */
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

	/** 删掉建租户时初始化的部门、岗位、角色、用户。平台租户不在这里清。跨租户清理必须忽略当前会话的租户条件。 */
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
		roleMapper.deleteByQuery(QueryWrapper.create().eq("tenant_id", code));
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
