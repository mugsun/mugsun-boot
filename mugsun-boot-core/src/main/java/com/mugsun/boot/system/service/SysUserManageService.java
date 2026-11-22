package com.mugsun.boot.system.service;

import cn.dev33.satoken.stp.StpUtil;
import com.mugsun.boot.common.constant.NotifyConstants;
import com.mugsun.boot.common.mask.FieldMaskContext;
import com.mugsun.boot.common.tx.AfterCommit;
import com.mugsun.boot.datascope.DataScopeContext;
import com.mugsun.boot.log.AuditService;
import com.mugsun.boot.notify.api.NotifyReceiver;
import com.mugsun.boot.notify.api.NotifySendApi;
import com.mugsun.boot.security.SecurityPolicyService;
import com.mugsun.boot.system.entity.SysUser;
import com.mugsun.boot.system.entity.SysUserRole;
import com.mugsun.boot.system.mapper.SysRoleMapper;
import com.mugsun.boot.system.mapper.SysUserMapper;
import com.mugsun.boot.system.mapper.SysUserRoleMapper;
import com.mugsun.boot.tenant.TenantContext;
import com.mugsun.boot.tenant.TenantValidator;
import com.mugsun.core.i18n.message.I18n;
import com.mugsun.core.tool.exception.ServiceException;
import com.mybatisflex.core.query.QueryWrapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;

/**
 * 用户管理写路径：新增/编辑/删除及角色同步，统一在事务内完成（任一步失败整体回滚）。
 * 权限、字段脱敏、入参规范化、组织归属与目标可操作性校验仍由 Controller 前置完成。
 * <p>新增、编辑、导入写完以后，在同一事务里按操作者的数据范围复核一次（{@link UserDataScopeGuard#assertStillCovers}）：
 * 保存的结果落到范围之外（比如部门选了别人管的部门）就整体回滚。
 * <p>编辑和导入覆盖把用户停用时，和单独的停用操作同一套规矩：不能停用自己，提交后踢掉这名用户的全部会话。
 */
@Service
public class SysUserManageService {

	private static final Logger log = LoggerFactory.getLogger(SysUserManageService.class);

	private final SysUserMapper userMapper;
	private final SysUserRoleMapper userRoleMapper;
	private final SysRoleMapper roleMapper;
	private final PasswordEncoder passwordEncoder;
	private final AuditService auditService;
	private final SecurityPolicyService securityPolicyService;
	private final TenantValidator tenantValidator;
	private final NotifySendApi notifySendApi;
	private final UserDataScopeGuard dataScopeGuard;

	public SysUserManageService(SysUserMapper userMapper, SysUserRoleMapper userRoleMapper, SysRoleMapper roleMapper,
								PasswordEncoder passwordEncoder, AuditService auditService,
								SecurityPolicyService securityPolicyService, TenantValidator tenantValidator,
								NotifySendApi notifySendApi, UserDataScopeGuard dataScopeGuard) {
		this.userMapper = userMapper;
		this.userRoleMapper = userRoleMapper;
		this.roleMapper = roleMapper;
		this.passwordEncoder = passwordEncoder;
		this.auditService = auditService;
		this.securityPolicyService = securityPolicyService;
		this.tenantValidator = tenantValidator;
		this.notifySendApi = notifySendApi;
		this.dataScopeGuard = dataScopeGuard;
	}

	/**
	 * 新建用户：配额锁内查重+插入、按数据范围复核、记密码历史、提交后欢迎通知、挂角色（同一事务）。
	 * <p>口令留空由服务端生成一次性口令；管理员代填的口令要过口令强度规则。两种情况都要求本人首次登录先改密。
	 * @param operatorScope 操作者保存前的数据范围，取自 {@link UserDataScopeGuard#operatorScope()}
	 * @return 服务端生成的一次性口令明文（调用方仅通过 API 响应交给管理员；勿打日志）；管理员代填口令时返回 null
	 */
	@Transactional(rollbackFor = Exception.class)
	public String create(SysUser user, DataScopeContext.Ctx operatorScope) {
		// 服务端清洗：审计字段与租户归属一律服务端裁定（Flex 仅对 null tenantId 才填充当前租户）
		user.sanitizeForInsert();
		user.setTenantId(null);
		if (user.getIsLeader() == null) {
			user.setIsLeader(0);
		}
		boolean generated = user.getPassword() == null || user.getPassword().isBlank();
		String raw;
		if (generated) {
			raw = securityPolicyService.generateOneTimePassword();
		} else {
			// 管理员代填的口令和用户自己设的口令过同一套强度规则
			raw = user.getPassword();
			securityPolicyService.validateComplexity(raw);
		}
		user.setPassword(passwordEncoder.encode(raw));
		// 系统生成的也好、管理员代填的也好，都不是本人设的口令：首次登录一律先改密，请求里带的值不算数
		user.setMustChangePassword(1);
		// 账号配额检查+插入在租户级 Redis 锁内串行（防并发 TOCTOU 超额；集群安全）
		tenantValidator.quotaLocked(TenantContext.current(), () -> {
			tenantValidator.assertAccountQuota(TenantContext.current());
			assertUsernameFree(user.getUsername(), null);
			try {
				userMapper.insert(user);
			} catch (DuplicateKeyException ex) {
				throw new ServiceException(I18n.get("账号「{0}」已经有人用过。请换一个账号", user.getUsername()));
			}
			return null;
		});
		dataScopeGuard.assertStillCovers(operatorScope, user.getId());
		securityPolicyService.logPassword(user.getId(), user.getPassword());
		notifyWelcome(user);
		syncUserRoles(user.getId(), user.getRoleIds());
		return generated ? raw : null;
	}

	/**
	 * 编辑用户：更新+按数据范围复核+审计+角色同步（同一事务）。
	 * @param before Controller 已脱敏读出的修改前镜像（用于审计）
	 * @param operatorScope 操作者保存前的数据范围，取自 {@link UserDataScopeGuard#operatorScope()}
	 */
	@Transactional(rollbackFor = Exception.class)
	public void update(SysUser user, SysUser before, DataScopeContext.Ctx operatorScope) {
		assertUsernameFree(user.getUsername(), user.getId());
		// 编辑不改口令：带了口令就拒绝，不悄悄忽略（调用方会以为改成了）。换口令统一走「重置密码」
		if (user.getPassword() != null && !user.getPassword().isBlank()) {
			throw new ServiceException("编辑用户不能改密码。要给这名用户换密码，请在用户列表里点「重置密码」");
		}
		user.setPassword(null);
		assertNotDisablingSelf(user.getId(), user.getStatus());
		// #81 B1：must_change_password 仅 reset/create/改密/忘记密码写；编辑一律忽略，防客户端/默认 0 清标记
		user.setMustChangePassword(null);
		user.sanitizeForUpdate();
		user.setTenantId(null);
		try {
			userMapper.update(user);
		} catch (DuplicateKeyException ex) {
			throw new ServiceException(I18n.get("账号「{0}」已经有人用过。请换一个账号", user.getUsername()));
		}
		dataScopeGuard.assertStillCovers(operatorScope, user.getId());
		kickoutIfDisabled(user.getId(), user.getStatus());
		// 审计前后镜像恒脱敏读：与操作者角色无关，敏感字段永不落明文入审计
		SysUser after = FieldMaskContext.maskedRead(() -> userMapper.selectOneById(user.getId()));
		if (before != null) {
			before.setPassword(null);
		}
		if (after != null) {
			after.setPassword(null);
		}
		Object operator = StpUtil.getLoginIdDefaultNull();
		auditService.record("sys_user", user.getId().toString(), before, after,
			operator == null ? null : operator.toString());
		syncUserRoles(user.getId(), user.getRoleIds());
	}

	/**
	 * 导入新增一行：配额锁内检查并插入、按数据范围复核、记密码历史。
	 * 一行一个事务：复核不过或中途出错，这一行整体回滚，不影响其他行。
	 * @param user 调用方已填好字段（含加密后的口令）并做过 sanitizeForInsert 的新用户
	 */
	@Transactional(rollbackFor = Exception.class)
	public void importCreate(SysUser user, String tenantId, DataScopeContext.Ctx operatorScope) {
		tenantValidator.quotaLocked(tenantId, () -> {
			tenantValidator.assertAccountQuota(tenantId);
			userMapper.insert(user);
			return null;
		});
		dataScopeGuard.assertStillCovers(operatorScope, user.getId());
		securityPolicyService.logPassword(user.getId(), user.getPassword());
	}

	/**
	 * 导入覆盖一行：更新后按数据范围复核，同一事务。
	 * @param patch 只带 id 和要覆盖的字段，空字段不更新
	 */
	@Transactional(rollbackFor = Exception.class)
	public void importOverwrite(SysUser patch, DataScopeContext.Ctx operatorScope) {
		assertNotDisablingSelf(patch.getId(), patch.getStatus());
		userMapper.update(patch);
		dataScopeGuard.assertStillCovers(operatorScope, patch.getId());
		kickoutIfDisabled(patch.getId(), patch.getStatus());
	}

	/**
	 * 编辑、导入不能把当前登录的账号停用。单独的停用操作本来就不允许对自己用；
	 * 停用会踢下线，放行的话操作者当场把自己锁在外面。
	 */
	private void assertNotDisablingSelf(Long userId, Integer newStatus) {
		if (!isDisabled(newStatus)) {
			return;
		}
		Object operator = StpUtil.getLoginIdDefaultNull();
		if (operator != null && operator.toString().equals(String.valueOf(userId))) {
			throw new ServiceException("不能停用当前登录的账号。请把状态改回启用，或换一个账号登录后再停用");
		}
	}

	/**
	 * 这次保存把状态写成了停用：提交后踢掉这名用户的全部会话，回滚则不踢。
	 * 停用状态只在登录时检查，不踢的话已登录的会话在令牌过期前一直能用。
	 */
	private void kickoutIfDisabled(Long userId, Integer savedStatus) {
		if (isDisabled(savedStatus)) {
			AfterCommit.execute(() -> StpUtil.kickout(userId));
		}
	}

	private static boolean isDisabled(Integer status) {
		return status != null && status == 0;
	}

	/** 删除用户：清角色关联 → 删用户（同一事务）；会话回收在提交后执行，回滚则不踢 */
	@Transactional(rollbackFor = Exception.class)
	public void removeUsers(List<SysUser> users) {
		List<Long> ids = new ArrayList<>();
		for (SysUser user : users) {
			userRoleMapper.deleteByQuery(QueryWrapper.create().eq("user_id", user.getId()));
			userMapper.deleteById(user.getId());
			ids.add(user.getId());
		}
		// 删除即回收会话（旧 token 默认 30 天有效，必须同步踢）
		AfterCommit.execute(() -> ids.forEach(StpUtil::kickout));
	}

	/**
	 * 角色同步：roleIds 为 null 不处理（兼容旧调用）；否则去重保序后全量替换。
	 * 校验角色存在且属当前租户（Flex 租户隔离自动过滤他租户角色，数量不符即含越权/不存在 id）。
	 */
	@Transactional(rollbackFor = Exception.class)
	public void syncUserRoles(Long userId, List<Long> roleIds) {
		if (roleIds == null) {
			return;
		}
		LinkedHashSet<Long> unique = new LinkedHashSet<>(roleIds);
		if (unique.contains(null)) {
			throw new ServiceException("有角色不存在，或属于其他租户。请重新选择角色");
		}
		if (!unique.isEmpty()
			&& roleMapper.selectCountByQuery(QueryWrapper.create().in("id", unique)) != unique.size()) {
			throw new ServiceException("有角色不存在，或属于其他租户。请重新选择角色");
		}
		userRoleMapper.deleteByQuery(QueryWrapper.create().eq("user_id", userId));
		for (Long roleId : unique) {
			SysUserRole ur = new SysUserRole();
			ur.setUserId(userId);
			ur.setRoleId(roleId);
			userRoleMapper.insert(ur);
		}
	}

	private void assertUsernameFree(String username, Long selfId) {
		if (username == null || username.isBlank()) {
			return;
		}
		SysUser taken = userMapper.selectOneByQuery(QueryWrapper.create().eq("username", username));
		if (taken != null && (selfId == null || !taken.getId().equals(selfId))) {
			throw new ServiceException(I18n.get("账号「{0}」已经有人在用。请换一个账号，或编辑原来那个用户", username));
		}
	}

	/** 新用户欢迎通知：事务提交后发送；通知失败不阻断建用户（记日志） */
	private void notifyWelcome(SysUser user) {
		Long userId = user.getId();
		String name = user.getNickname() == null || user.getNickname().isBlank()
			? user.getUsername() : user.getNickname();
		String email = user.getEmail();
		String phone = user.getPhone();
		AfterCommit.execute(() -> {
			try {
				notifySendApi.send(NotifyConstants.TEMPLATE_WELCOME,
					List.of(NotifyReceiver.of(userId, name, email, phone)),
					java.util.Map.of("name", name));
			} catch (Exception e) {
				log.warn("新用户欢迎通知发送失败 userId={} err={}", userId, e.getMessage());
			}
		});
	}
}
