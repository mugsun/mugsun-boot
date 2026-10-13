package com.mugsun.boot.system.service;

import cn.dev33.satoken.stp.StpUtil;
import com.mugsun.boot.common.constant.NotifyConstants;
import com.mugsun.boot.common.mask.FieldMaskContext;
import com.mugsun.boot.common.tx.AfterCommit;
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

	public SysUserManageService(SysUserMapper userMapper, SysUserRoleMapper userRoleMapper, SysRoleMapper roleMapper,
								PasswordEncoder passwordEncoder, AuditService auditService,
								SecurityPolicyService securityPolicyService, TenantValidator tenantValidator,
								NotifySendApi notifySendApi) {
		this.userMapper = userMapper;
		this.userRoleMapper = userRoleMapper;
		this.roleMapper = roleMapper;
		this.passwordEncoder = passwordEncoder;
		this.auditService = auditService;
		this.securityPolicyService = securityPolicyService;
		this.tenantValidator = tenantValidator;
		this.notifySendApi = notifySendApi;
	}

	/** 新建用户：配额锁内查重+插入、记密码历史、提交后欢迎通知、挂角色（同一事务） */
	@Transactional(rollbackFor = Exception.class)
	public void create(SysUser user) {
		// 服务端清洗：审计字段与租户归属一律服务端裁定（Flex 仅对 null tenantId 才填充当前租户）
		user.sanitizeForInsert();
		user.setTenantId(null);
		if (user.getIsLeader() == null) {
			user.setIsLeader(0);
		}
		String raw = (user.getPassword() == null || user.getPassword().isBlank())
			? securityPolicyService.getInitPassword() : user.getPassword();
		user.setPassword(passwordEncoder.encode(raw));
		// 账号配额检查+插入在租户级 Redis 锁内串行（防并发 TOCTOU 超额；集群安全）
		tenantValidator.quotaLocked(TenantContext.current(), () -> {
			tenantValidator.assertAccountQuota(TenantContext.current());
			assertUsernameFree(user.getUsername(), null);
			try {
				userMapper.insert(user);
			} catch (DuplicateKeyException ex) {
				throw new ServiceException("账号「" + user.getUsername() + "」已经有人用过。请换一个账号");
			}
			return null;
		});
		securityPolicyService.logPassword(user.getId(), user.getPassword());
		notifyWelcome(user);
		syncUserRoles(user.getId(), user.getRoleIds());
	}

	/**
	 * 编辑用户：更新+审计+角色同步（同一事务）。
	 * @param before Controller 已脱敏读出的修改前镜像（用于审计）
	 */
	@Transactional(rollbackFor = Exception.class)
	public void update(SysUser user, SysUser before) {
		assertUsernameFree(user.getUsername(), user.getId());
		if (user.getPassword() != null && !user.getPassword().isBlank()) {
			user.setPassword(passwordEncoder.encode(user.getPassword()));
		}
		user.sanitizeForUpdate();
		user.setTenantId(null);
		try {
			userMapper.update(user);
		} catch (DuplicateKeyException ex) {
			throw new ServiceException("账号「" + user.getUsername() + "」已经有人用过。请换一个账号");
		}
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
			throw new ServiceException("账号「" + username + "」已经有人在用。请换一个账号，或编辑原来那个用户");
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
