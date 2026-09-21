package com.mugsun.boot.system.controller;

import cn.dev33.satoken.annotation.SaCheckLogin;
import cn.dev33.satoken.annotation.SaCheckPermission;
import cn.dev33.satoken.stp.StpUtil;
import com.mugsun.boot.common.constant.FieldMaskConstants;
import com.mugsun.boot.common.constant.NotifyConstants;
import com.mugsun.boot.common.constant.UserConstants;
import com.mugsun.boot.common.tx.AfterCommit;
import com.mugsun.boot.datascope.DataScope;
import com.mugsun.boot.log.AuditService;
import com.mugsun.boot.log.OperationLog;
import com.mugsun.boot.notify.api.NotifyReceiver;
import com.mugsun.boot.notify.api.NotifySendApi;
import com.mugsun.boot.system.entity.SysUser;
import com.mugsun.boot.system.entity.SysUserRole;
import com.mugsun.boot.system.excel.SysUserExcel;
import com.mugsun.boot.system.excel.SysUserExportExcel;
import com.mugsun.boot.system.mapper.SysUserMapper;
import com.mugsun.boot.system.mapper.SysUserRoleMapper;
import com.mugsun.boot.system.payload.StatusParam;
import com.mugsun.boot.system.payload.UserGrantParam;
import com.mugsun.boot.system.payload.UserImportResult;
import com.mugsun.core.tool.api.R;
import com.mugsun.core.web.excel.ExcelUtil;
import com.mybatisflex.core.paginate.Page;
import com.mybatisflex.core.query.QueryColumn;
import com.mybatisflex.core.query.QueryWrapper;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;
import java.util.Map;

/**
 * 用户管理
 */
@RestController
@RequestMapping("/system/user")
@SaCheckLogin
public class SysUserController {

	private static final Logger log = LoggerFactory.getLogger(SysUserController.class);

	private final SysUserMapper userMapper;
	private final PasswordEncoder passwordEncoder;
	private final AuditService auditService;
	private final SysUserRoleMapper userRoleMapper;
	private final com.mugsun.boot.system.mapper.SysRoleMapper roleMapper;
	private final com.mugsun.boot.system.mapper.SysDeptMapper deptMapper;
	private final com.mugsun.boot.system.mapper.SysPostMapper postMapper;
	private final com.mugsun.boot.security.SecurityPolicyService securityPolicyService;
	private final com.mugsun.boot.tenant.TenantValidator tenantValidator;
	private final NotifySendApi notifySendApi;

	public SysUserController(SysUserMapper userMapper, PasswordEncoder passwordEncoder, AuditService auditService,
							 SysUserRoleMapper userRoleMapper,
							 com.mugsun.boot.system.mapper.SysRoleMapper roleMapper,
							 com.mugsun.boot.system.mapper.SysDeptMapper deptMapper,
							 com.mugsun.boot.system.mapper.SysPostMapper postMapper,
							 com.mugsun.boot.security.SecurityPolicyService securityPolicyService,
							 com.mugsun.boot.tenant.TenantValidator tenantValidator,
							 NotifySendApi notifySendApi) {
		this.userMapper = userMapper;
		this.passwordEncoder = passwordEncoder;
		this.auditService = auditService;
		this.userRoleMapper = userRoleMapper;
		this.roleMapper = roleMapper;
		this.deptMapper = deptMapper;
		this.postMapper = postMapper;
		this.securityPolicyService = securityPolicyService;
		this.tenantValidator = tenantValidator;
		this.notifySendApi = notifySendApi;
	}

	@GetMapping("/page")
	@SaCheckPermission("sys:user:list")
	@DataScope
	public R<Page<SysUser>> page(@RequestParam(defaultValue = "1") long pageNum,
								 @RequestParam(defaultValue = "10") long pageSize,
								 @RequestParam(required = false) String username,
								 @RequestParam(required = false) String nickname,
								 @RequestParam(required = false) String phone,
								 @RequestParam(required = false) Integer status,
								 @RequestParam(required = false) Long deptId) {
		QueryWrapper query = buildUserQuery(username, nickname, phone, status, deptId);
		// 行级数据权限：@DataScope 激活后由数据权限方言自动注入 OR 并集条件（无需手工 apply）
		Page<SysUser> page = userMapper.paginate(pageNum, pageSize, query);
		// 密码脱敏
		page.getRecords().forEach(u -> u.setPassword(null));
		enrichOrg(page.getRecords());
		return R.data(page);
	}

	/** 用户列表查询条件构造（page 与 export 共用：值走参数化绑定，LIKE 前后模糊） */
	private QueryWrapper buildUserQuery(String username, String nickname, String phone, Integer status, Long deptId) {
		QueryWrapper query = QueryWrapper.create().orderBy("id", false);
		if (username != null && !username.isBlank()) {
			query.like("username", username.trim());
		}
		if (nickname != null && !nickname.isBlank()) {
			query.like("nickname", nickname.trim());
		}
		if (phone != null && !phone.isBlank()) {
			query.like("phone", phone.trim());
		}
		if (status != null) {
			query.eq("status", status);
		}
		if (deptId != null) {
			query.eq("dept_id", deptId);
		}
		return query;
	}

	/** 组织信息富化：部门/岗位/角色名/主管名批量回填（按 id 集批量查，避免 N+1） */
	private void enrichOrg(List<SysUser> records) {
		if (records == null || records.isEmpty()) {
			return;
		}
		java.util.Set<Long> deptIds = new java.util.HashSet<>();
		java.util.Set<Long> postIds = new java.util.HashSet<>();
		java.util.Set<Long> leaderIds = new java.util.HashSet<>();
		List<Long> userIds = new java.util.ArrayList<>();
		for (SysUser u : records) {
			if (u.getDeptId() != null) {
				deptIds.add(u.getDeptId());
			}
			if (u.getPostId() != null) {
				postIds.add(u.getPostId());
			}
			if (u.getLeaderId() != null) {
				leaderIds.add(u.getLeaderId());
			}
			userIds.add(u.getId());
		}
		Map<Long, String> deptMap = new java.util.HashMap<>();
		if (!deptIds.isEmpty()) {
			deptMapper.selectListByQuery(QueryWrapper.create().in("id", deptIds))
				.forEach(d -> deptMap.put(d.getId(), d.getDeptName()));
		}
		Map<Long, String> postMap = new java.util.HashMap<>();
		if (!postIds.isEmpty()) {
			postMapper.selectListByQuery(QueryWrapper.create().in("id", postIds))
				.forEach(p -> postMap.put(p.getId(), p.getPostName()));
		}
		Map<Long, String> leaderMap = new java.util.HashMap<>();
		if (!leaderIds.isEmpty()) {
			userMapper.selectListByQuery(QueryWrapper.create().in("id", leaderIds))
				.forEach(l -> {
					String label = l.getRealName() != null && !l.getRealName().isBlank()
						? l.getRealName()
						: (l.getNickname() != null ? l.getNickname() : l.getUsername());
					leaderMap.put(l.getId(), label);
				});
		}
		// 角色名：user_role 批量取回后按角色 id 集查名
		List<SysUserRole> userRoles = userRoleMapper.selectListByQuery(QueryWrapper.create().in("user_id", userIds));
		Map<Long, String> roleNameMap = new java.util.HashMap<>();
		if (!userRoles.isEmpty()) {
			roleMapper.selectListByQuery(QueryWrapper.create().in("id",
					userRoles.stream().map(SysUserRole::getRoleId).distinct().toList()))
				.forEach(r -> roleNameMap.put(r.getId(), r.getRoleName()));
		}
		Map<Long, List<String>> namesByUser = new java.util.HashMap<>();
		for (SysUserRole ur : userRoles) {
			String name = roleNameMap.get(ur.getRoleId());
			if (name != null) {
				namesByUser.computeIfAbsent(ur.getUserId(), k -> new java.util.ArrayList<>()).add(name);
			}
		}
		for (SysUser u : records) {
			u.setDeptName(deptMap.get(u.getDeptId()));
			u.setPostName(postMap.get(u.getPostId()));
			u.setLeaderName(leaderMap.get(u.getLeaderId()));
			List<String> names = namesByUser.get(u.getId());
			u.setRoleNames(names == null ? null : String.join("、", names));
		}
	}

	@GetMapping("/detail")
	@SaCheckPermission("sys:user:list")
	@DataScope
	public R<SysUser> detail(@RequestParam Long id) {
		// 手机号/身份证的 明文/脱敏/不可见 由按角色决策的脱敏处理器统一裁决（绑权限码而非绑端点，与 page 表现一致）
		// 行级：按 id 走 QueryWrapper 查询以经 @DataScope 方言注入数据范围（selectOneById 直查会绕过行级范围），越范围返 null
		SysUser user = userMapper.selectOneByQuery(QueryWrapper.create().eq("id", id));
		if (user != null) {
			user.setPassword(null);
			// 角色回显（建档弹窗编辑态）
			user.setRoleIds(userRoleMapper.selectListByQuery(QueryWrapper.create().eq("user_id", id))
				.stream().map(SysUserRole::getRoleId).toList());
			enrichOrg(List.of(user));
		}
		return R.data(user);
	}

	/** 部门/岗位归属校验：存在且属当前租户（防挂到他租户组织越权取数） */
	private void assertOrgValid(SysUser user) {
		if (user.getDeptId() != null
			&& deptMapper.selectCountByQuery(QueryWrapper.create().eq("id", user.getDeptId())) == 0) {
			throw new com.mugsun.core.tool.exception.ServiceException("部门不存在");
		}
		if (user.getPostId() != null
			&& postMapper.selectCountByQuery(QueryWrapper.create().eq("id", user.getPostId())) == 0) {
			throw new com.mugsun.core.tool.exception.ServiceException("岗位不存在");
		}
		if (user.getLeaderId() != null) {
			if (user.getId() != null && user.getLeaderId().equals(user.getId())) {
				throw new com.mugsun.core.tool.exception.ServiceException("直属主管不能是本人");
			}
			SysUser leader = userMapper.selectOneById(user.getLeaderId());
			if (leader == null) {
				throw new com.mugsun.core.tool.exception.ServiceException("直属主管不存在");
			}
		}
	}

	/**
	 * 角色同步：roleIds 不为 null 时全量替换用户角色。
	 * 校验角色存在且属当前租户（Flex 租户隔离自动过滤他租户角色，数量不符即含越权 id）。
	 */
	private void syncUserRoles(Long userId, List<Long> roleIds) {
		if (roleIds == null) {
			return;
		}
		if (!roleIds.isEmpty()
			&& roleMapper.selectCountByQuery(QueryWrapper.create().in("id", roleIds)) != roleIds.size()) {
			throw new com.mugsun.core.tool.exception.ServiceException("存在无效角色");
		}
		userRoleMapper.deleteByQuery(QueryWrapper.create().eq("user_id", userId));
		for (Long roleId : roleIds) {
			SysUserRole ur = new SysUserRole();
			ur.setUserId(userId);
			ur.setRoleId(roleId);
			userRoleMapper.insert(ur);
		}
	}

	/** 用户下拉选项（value=id / label=昵称，供收件人选择等场景）；持码+数据范围约束，防全租户账号枚举。
	 *  成千账号场景不下全量：默认仅返回启用用户前 {@link UserConstants#USER_SELECT_LIMIT} 条，
	 *  keyword 远程搜索（用户名/昵称模糊）；ids 精确取（编辑回显，不限启用态/条数） */
	@GetMapping("/select")
	@SaCheckPermission("sys:user:list")
	@DataScope
	public R<List<java.util.Map<String, Object>>> select(@RequestParam(required = false) String keyword,
			@RequestParam(required = false) List<Long> ids) {
		QueryWrapper query = QueryWrapper.create();
		if (ids != null && !ids.isEmpty()) {
			query.in("id", ids);
		} else {
			query.eq("status", 1);
			if (keyword != null && !keyword.isBlank()) {
				String kw = keyword.trim();
				// QueryCondition.or 自动加 Brackets 分组（同 DataScopeEngine 惯用法），生成 AND (username LIKE ? OR nickname LIKE ?)
				query.and(new QueryColumn("username").like(kw).or(new QueryColumn("nickname").like(kw)));
			}
			query.orderBy("id", false).limit(UserConstants.USER_SELECT_LIMIT);
		}
		return R.data(userMapper.selectListByQuery(query).stream()
			.map(u -> {
				java.util.Map<String, Object> option = new java.util.HashMap<>();
				option.put("value", u.getId());
				option.put("label", (u.getNickname() == null ? u.getUsername() : u.getNickname())
					+ "（" + u.getUsername() + "）");
				return option;
			})
			.toList());
	}

	/**
	 * 切换是否主管（对齐 BladeX set-leader）：is_leader 0↔1。
	 * 标记为主管后可出现在 leader-list，供他人选直属主管。
	 */
	@PostMapping("/set-leader")
	@SaCheckPermission("sys:user:edit")
	@OperationLog("设置主管")
	public R<Void> setLeader(@RequestParam Long userId) {
		assertTargetOperable(userId, true);
		SysUser user = userMapper.selectOneById(userId);
		if (user == null) {
			throw new com.mugsun.core.tool.exception.ServiceException("用户不存在");
		}
		int next = (user.getIsLeader() != null && user.getIsLeader() == 1) ? 0 : 1;
		SysUser patch = new SysUser();
		patch.setId(userId);
		patch.setIsLeader(next);
		patch.sanitizeForUpdate();
		userMapper.update(patch);
		return R.success(next == 1 ? "已设为主管" : "已取消主管");
	}

	/** 当前用户的直属主管信息（单 id；无则空列表） */
	@GetMapping("/leader-info")
	@SaCheckPermission("sys:user:list")
	@DataScope
	public R<List<SysUser>> leaderInfo(@RequestParam Long userId) {
		SysUser user = userMapper.selectOneByQuery(QueryWrapper.create().eq("id", userId));
		if (user == null) {
			throw new com.mugsun.core.tool.exception.ServiceException("用户不存在");
		}
		if (user.getLeaderId() == null) {
			return R.data(List.of());
		}
		SysUser leader = userMapper.selectOneByQuery(QueryWrapper.create().eq("id", user.getLeaderId()));
		if (leader == null) {
			return R.data(List.of());
		}
		leader.setPassword(null);
		return R.data(List.of(leader));
	}

	/**
	 * 主管候选列表（is_leader=1 且启用）：供建档选直属主管。
	 * realName 模糊匹配真实姓名/昵称/用户名。
	 */
	@GetMapping("/leader-list")
	@SaCheckPermission("sys:user:list")
	@DataScope
	public R<List<java.util.Map<String, Object>>> leaderList(
			@RequestParam(required = false) String realName) {
		QueryWrapper query = QueryWrapper.create()
			.eq("is_leader", 1)
			.eq("status", 1);
		if (realName != null && !realName.isBlank()) {
			String kw = realName.trim();
			query.and(new QueryColumn("real_name").like(kw)
				.or(new QueryColumn("nickname").like(kw))
				.or(new QueryColumn("username").like(kw)));
		}
		query.orderBy("id", false).limit(UserConstants.USER_SELECT_LIMIT);
		return R.data(userMapper.selectListByQuery(query).stream()
			.map(u -> {
				java.util.Map<String, Object> option = new java.util.HashMap<>();
				option.put("value", u.getId());
				String name = u.getRealName() != null && !u.getRealName().isBlank()
					? u.getRealName()
					: (u.getNickname() != null ? u.getNickname() : u.getUsername());
				option.put("label", name + "（" + u.getUsername() + "）");
				option.put("realName", name);
				return option;
			})
			.toList());
	}

	@PostMapping("/submit")
	@OperationLog("保存用户")
	public R<Void> submit(@RequestBody SysUser user) {
		// 新增走 sys:user:add，编辑走 sys:user:edit（与前端按钮门控码对齐，避免"可见却越权失败"）
		StpUtil.checkPermission(user.getId() == null ? "sys:user:add" : "sys:user:edit");
		// 字段级写门控（新建/编辑一致，读写权对称）：无字段明文权则不得写该敏感字段——
		// 编辑时置 null 交 Flex update 忽略、保留原值（防"看不到明文却把脱敏串覆盖入库"的污染与越权改写）；新建时即不落该字段
		if (!StpUtil.hasPermission(FieldMaskConstants.PERM_USER_PHONE_PLAIN)) {
			user.setPhone(null);
		}
		if (!StpUtil.hasPermission(FieldMaskConstants.PERM_USER_ID_CARD_PLAIN)) {
			user.setIdCard(null);
		}
		// 回写防护（编辑场景）：脱敏串（含 *）或密文形态值一律视为「未修改」置 null 交 Flex 忽略，
		// 杜绝脱敏值污染入库与密文二次加密（读管线任何回显形态都不会毁数据）
		if (user.getPhone() != null && user.getPhone().contains("*")) {
			user.setPhone(null);
		}
		if (user.getIdCard() != null
			&& (user.getIdCard().contains("*") || com.mugsun.boot.common.crypto.Sm4Util.looksLikeCipher(user.getIdCard()))) {
			user.setIdCard(null);
		}
		// 手机号格式校验（短信登录按号取人，脏号即冒注面）
		if (user.getPhone() != null && !user.getPhone().isBlank() && !user.getPhone().matches("^1\\d{10}$")) {
			throw new com.mugsun.core.tool.exception.ServiceException("手机号格式不正确");
		}
		// 部门/岗位归属校验（建档挂组织，防跨租户）
		assertOrgValid(user);
		if (user.getId() == null) {
			// 服务端清洗：审计字段与租户归属一律服务端裁定（Flex 仅对 null tenantId 才填充当前租户）
			user.sanitizeForInsert();
			user.setTenantId(null);
			if (user.getIsLeader() == null) {
				user.setIsLeader(0);
			}
			// 账号配额检查+插入在租户级 Redis 锁内串行（防并发 TOCTOU 超额；集群安全）
			String raw = (user.getPassword() == null || user.getPassword().isBlank()) ? securityPolicyService.getInitPassword() : user.getPassword();
			user.setPassword(passwordEncoder.encode(raw));
			tenantValidator.quotaLocked(com.mugsun.boot.tenant.TenantContext.current(), () -> {
				tenantValidator.assertAccountQuota(com.mugsun.boot.tenant.TenantContext.current());
				userMapper.insert(user);
				return null;
			});
			securityPolicyService.logPassword(user.getId(), user.getPassword());
			notifyWelcome(user);
			// 建档挂角色（roleIds 非 null 才同步，保持旧调用方兼容）
			syncUserRoles(user.getId(), user.getRoleIds());
		} else {
			// 审计前后镜像恒脱敏读：与操作者角色无关，敏感字段永不落明文入审计
			SysUser before = com.mugsun.boot.common.mask.FieldMaskContext.maskedRead(() -> userMapper.selectOneById(user.getId()));
			if (before == null) {
				throw new com.mugsun.core.tool.exception.ServiceException("用户不存在");
			}
			assertTargetOperable(user.getId(), true);
			if (user.getPassword() != null && !user.getPassword().isBlank()) {
				user.setPassword(passwordEncoder.encode(user.getPassword()));
			}
			user.sanitizeForUpdate();
			user.setTenantId(null);
			userMapper.update(user);
			SysUser after = com.mugsun.boot.common.mask.FieldMaskContext.maskedRead(() -> userMapper.selectOneById(user.getId()));
			if (before != null) {
				before.setPassword(null);
			}
			if (after != null) {
				after.setPassword(null);
			}
			Object operator = StpUtil.getLoginIdDefaultNull();
			auditService.record("sys_user", user.getId().toString(), before, after,
				operator == null ? null : operator.toString());
			// 编辑挂角色（roleIds 非 null 才同步，保持旧调用方兼容）
			syncUserRoles(user.getId(), user.getRoleIds());
		}
		return R.success("操作成功");
	}

	@PostMapping("/remove")
	@SaCheckPermission("sys:user:remove")
	@OperationLog("删除用户")
	public R<Void> remove(@RequestBody List<Long> ids) {
		for (Long id : ids) {
			assertTargetOperable(id, false);
			userMapper.deleteById(id);
			// 删除即回收会话（逻辑删可挡新登录，但旧 token 默认 30 天有效，必须同步踢）
			StpUtil.kickout(id);
		}
		return R.success("删除成功");
	}

	/**
	 * 导出用户（与 page 同查询条件，受 @DataScope 行级范围约束）。
	 * 手机号导出形态由字段级权限裁决（@ColumnMask 读管线自动生效）：无查看权→空、无明文权→脱敏、有明文权→明文。
	 */
	@GetMapping("/export")
	@SaCheckPermission("sys:user:list")
	@DataScope
	public void export(HttpServletResponse response,
					   @RequestParam(required = false) String username,
					   @RequestParam(required = false) String nickname,
					   @RequestParam(required = false) String phone,
					   @RequestParam(required = false) Integer status,
					   @RequestParam(required = false) Long deptId) {
		List<SysUser> users = userMapper.selectListByQuery(buildUserQuery(username, nickname, phone, status, deptId));
		enrichOrg(users);
		List<SysUserExportExcel> rows = users.stream().map(user -> {
			SysUserExportExcel row = new SysUserExportExcel();
			row.setUsername(user.getUsername());
			row.setNickname(user.getNickname());
			row.setDeptName(user.getDeptName());
			row.setRoleNames(user.getRoleNames());
			row.setEmail(user.getEmail());
			row.setPhone(user.getPhone());
			row.setStatus(user.getStatus());
			row.setCreateTime(user.getCreateTime());
			return row;
		}).toList();
		ExcelUtil.export(response, "用户数据", "用户", rows, SysUserExportExcel.class);
	}

	/** 导入模板下载：表头 + 一行示例（权限码与导入一致） */
	@GetMapping("/import-template")
	@SaCheckPermission("sys:user:add")
	public void importTemplate(HttpServletResponse response) {
		SysUserExcel example = new SysUserExcel();
		example.setUsername("zhangsan");
		example.setNickname("张三");
		example.setDeptName("研发中心");
		example.setPostName("开发工程师");
		example.setEmail("zhangsan@example.com");
		example.setPhone("13800000000");
		example.setStatus(1);
		ExcelUtil.export(response, "用户导入模板", "用户导入", List.of(example), SysUserExcel.class);
	}

	/**
	 * 导入用户：逐行处理，单行失败不影响其余行，返回结构化成败明细。
	 * 空用户名行整行跳过（不计成败）；格式错误（手机号不合规/状态非法）记失败行；
	 * 已存在账号：updateSupport=false 记失败「已存在」，=true 覆盖更新 昵称/状态/邮箱/部门/岗位（不更新密码与手机号）。
	 */
	@PostMapping("/import")
	@SaCheckPermission("sys:user:add")
	@OperationLog("导入用户")
	public R<UserImportResult> importUser(@RequestParam("file") MultipartFile file,
										  @RequestParam(required = false, defaultValue = "false") boolean updateSupport) {
		List<SysUserExcel> rows = ExcelUtil.read(file, SysUserExcel.class);
		String tenant = com.mugsun.boot.tenant.TenantContext.current();
		int success = 0;
		List<UserImportResult.FailRow> failList = new java.util.ArrayList<>();
		for (int i = 0; i < rows.size(); i++) {
			SysUserExcel row = rows.get(i);
			String username = row.getUsername() == null ? null : row.getUsername().trim();
			if (username == null || username.isEmpty()) {
				continue;
			}
			try {
				importRow(row, username, updateSupport, tenant);
				success++;
			} catch (com.mugsun.core.tool.exception.ServiceException e) {
				// rowIndex 为 Excel 物理行号：表头占第 1 行，首条数据为第 2 行
				failList.add(new UserImportResult.FailRow(i + 2, username, e.getMessage()));
			} catch (org.springframework.dao.DuplicateKeyException e) {
				// 唯一约束冲突（用户名/手机号与他账号重复）：记失败行而非整批 500
				failList.add(new UserImportResult.FailRow(i + 2, username, "与已有账号数据冲突（用户名或手机号重复）"));
			}
		}
		return R.data(new UserImportResult(success, failList.size(), failList));
	}

	/** 导入单行：格式校验 → 部门/岗位名解析 → 新增或按 updateSupport 覆盖 */
	private void importRow(SysUserExcel row, String username, boolean updateSupport, String tenant) {
		String phone = trimToNull(row.getPhone());
		// 手机号格式校验（与 submit 同规：短信登录按号取人，脏号即冒注面）
		if (phone != null && !phone.matches("^1\\d{10}$")) {
			throw new com.mugsun.core.tool.exception.ServiceException("手机号格式不正确");
		}
		Integer status = row.getStatus();
		if (status != null && status != 0 && status != 1) {
			throw new com.mugsun.core.tool.exception.ServiceException("状态须为1或0");
		}
		Long deptId = resolveDeptId(row.getDeptName());
		Long postId = resolvePostId(row.getPostName());
		SysUser existing = userMapper.selectOneByQuery(QueryWrapper.create().eq("username", username));
		if (existing != null) {
			if (!updateSupport) {
				throw new com.mugsun.core.tool.exception.ServiceException("已存在");
			}
			// 覆盖更新：仅模板承载字段（不更新密码/手机号）；空白字段视为「不更新」，置 null 交 Flex update 忽略、保留原值
			assertTargetOperable(existing.getId(), true);
			SysUser update = new SysUser();
			update.setId(existing.getId());
			update.setNickname(trimToNull(row.getNickname()));
			update.setStatus(status);
			update.setEmail(trimToNull(row.getEmail()));
			update.setDeptId(deptId);
			update.setPostId(postId);
			update.sanitizeForUpdate();
			userMapper.update(update);
			return;
		}
		// 新增：初始密码入库（与 submit 同款收尾：审计字段服务端清洗 + 配额锁内检查+插入防并发超额）
		SysUser user = new SysUser();
		user.setUsername(username);
		user.setNickname(trimToNull(row.getNickname()));
		user.setStatus(status == null ? 1 : status);
		user.setEmail(trimToNull(row.getEmail()));
		user.setPhone(phone);
		user.setDeptId(deptId);
		user.setPostId(postId);
		user.setPassword(passwordEncoder.encode(securityPolicyService.getInitPassword()));
		// 模板不承载「是否负责人」，须显式给默认值：不给则 ORM 写入显式 NULL，撞 is_leader 非空约束整批 500
		user.setIsLeader(0);
		user.sanitizeForInsert();
		user.setTenantId(null);
		tenantValidator.quotaLocked(tenant, () -> {
			tenantValidator.assertAccountQuota(tenant);
			userMapper.insert(user);
			return null;
		});
		securityPolicyService.logPassword(user.getId(), user.getPassword());
	}

	/** 部门名 → id（空名不挂；无匹配记失败行；重名取首条） */
	private Long resolveDeptId(String deptName) {
		String name = trimToNull(deptName);
		if (name == null) {
			return null;
		}
		List<com.mugsun.boot.system.entity.SysDept> depts = deptMapper
			.selectListByQuery(QueryWrapper.create().eq("dept_name", name));
		if (depts.isEmpty()) {
			throw new com.mugsun.core.tool.exception.ServiceException("部门不存在：" + name);
		}
		return depts.get(0).getId();
	}

	/** 岗位名 → id（空名不挂；无匹配记失败行；重名取首条） */
	private Long resolvePostId(String postName) {
		String name = trimToNull(postName);
		if (name == null) {
			return null;
		}
		List<com.mugsun.boot.system.entity.SysPost> posts = postMapper
			.selectListByQuery(QueryWrapper.create().eq("post_name", name));
		if (posts.isEmpty()) {
			throw new com.mugsun.core.tool.exception.ServiceException("岗位不存在：" + name);
		}
		return posts.get(0).getId();
	}

	/** 空白字符串归一为 null（导入语义：空白即「未填写」） */
	private String trimToNull(String value) {
		if (value == null) {
			return null;
		}
		String trimmed = value.trim();
		return trimmed.isEmpty() ? null : trimmed;
	}

	/** 新用户欢迎通知：提交后按统一模板多渠道 fan-out；通知失败不阻断建用户（记日志） */
	private void notifyWelcome(SysUser user) {
		Long userId = user.getId();
		String name = user.getNickname() == null || user.getNickname().isBlank()
			? user.getUsername() : user.getNickname();
		String email = user.getEmail();
		String phone = user.getPhone();
		AfterCommit.execute(() -> {
			try {
				notifySendApi.send(NotifyConstants.TEMPLATE_WELCOME,
					java.util.List.of(NotifyReceiver.of(userId, name, email, phone)),
					java.util.Map.of("name", name));
			} catch (Exception e) {
				log.warn("新用户欢迎通知发送失败 userId={} err={}", userId, e.getMessage());
			}
		});
	}

	/** 重置密码为初始密码（批量；事务原子，重置即踢全部在线端强制重登） */
	@PostMapping("/reset-password")
	@SaCheckPermission("sys:user:reset")
	@OperationLog("重置密码")
	@org.springframework.transaction.annotation.Transactional(rollbackFor = Exception.class)
	public R<Void> resetPassword(@RequestBody List<Long> ids) {
		for (Long id : ids) {
			assertTargetOperable(id, true);
			SysUser user = new SysUser();
			user.setId(id);
			String encoded = passwordEncoder.encode(securityPolicyService.getInitPassword());
			user.setPassword(encoded);
			userMapper.update(user);
			securityPolicyService.logPassword(id, encoded);
			StpUtil.kickout(id);
		}
		return R.success("密码已重置为初始密码");
	}

	/** 启用 / 停用用户（停用即踢全部在线端） */
	@PostMapping("/status")
	@SaCheckPermission("sys:user:edit")
	@OperationLog("变更用户状态")
	public R<Void> status(@RequestBody StatusParam param) {
		assertTargetOperable(param.id(), false);
		SysUser user = new SysUser();
		user.setId(param.id());
		user.setStatus(param.status());
		userMapper.update(user);
		if (param.status() != null && param.status() == 0) {
			StpUtil.kickout(param.id());
		}
		return R.success("操作成功");
	}

	/** 查询用户已授权角色 id 集合（授权回显） */
	@GetMapping("/role-ids")
	@SaCheckPermission("sys:user:grant")
	public R<List<Long>> roleIds(@RequestParam Long userId) {
		assertUserInScope(userId);
		List<Long> ids = userRoleMapper
			.selectListByQuery(QueryWrapper.create().eq("user_id", userId))
			.stream()
			.map(SysUserRole::getRoleId)
			.toList();
		return R.data(ids);
	}

	/** 用户授权角色（事务原子；用户与角色均须属当前租户——中间表 sys_user_role 无 tenant_id，必须先证归属） */
	@PostMapping("/grant")
	@SaCheckPermission("sys:user:grant")
	@OperationLog("用户授权")
	@org.springframework.transaction.annotation.Transactional(rollbackFor = Exception.class)
	public R<Void> grant(@RequestBody UserGrantParam param) {
		assertUserInScope(param.userId());
		if (param.roleIds() != null) {
			for (Long roleId : param.roleIds()) {
				com.mugsun.boot.system.entity.SysRole role = roleMapper.selectOneById(roleId);
				if (role == null) {
					throw new com.mugsun.core.tool.exception.ServiceException("角色不存在：" + roleId);
				}
				// 内置 admin 角色仅平台超管或同为 admin 角色持有者可授（防低权角色自助升格）
				if (com.mugsun.boot.common.constant.RoleConstants.ADMIN.equals(role.getRoleCode())
					&& !com.mugsun.boot.tenant.TenantContext.isPlatformSuperAdmin()
					&& !StpUtil.getRoleList().contains(com.mugsun.boot.common.constant.RoleConstants.ADMIN)) {
					throw new com.mugsun.core.tool.exception.ServiceException("内置管理员角色仅管理员可授予");
				}
			}
		}
		userRoleMapper.deleteByQuery(QueryWrapper.create().eq("user_id", param.userId()));
		if (param.roleIds() != null) {
			for (Long roleId : param.roleIds()) {
				SysUserRole userRole = new SysUserRole();
				userRole.setUserId(param.userId());
				userRole.setRoleId(roleId);
				userRoleMapper.insert(userRole);
			}
		}
		return R.success("授权成功");
	}

	/** 用户归属校验：Flex 租户条件天然挡跨租户直查，null 即越界或不存在 */
	private void assertUserInScope(Long userId) {
		if (userId == null || userMapper.selectOneById(userId) == null) {
			throw new com.mugsun.core.tool.exception.ServiceException("用户不存在");
		}
	}

	/**
	 * 目标用户操作保护（对齐 RuoYi checkUserAllowed）：
	 * 内置 admin 角色持有者仅平台超管可操作（防租户内改密/停用/删除管理员致账号接管）；
	 * allowSelf=false 时禁止操作自己（防自杀/自锁）。
	 */
	private void assertTargetOperable(Long userId, boolean allowSelf) {
		assertUserInScope(userId);
		if (!allowSelf && StpUtil.getLoginIdAsLong() == userId) {
			throw new com.mugsun.core.tool.exception.ServiceException("不能对当前登录账号执行该操作");
		}
		if (!com.mugsun.boot.tenant.TenantContext.isPlatformSuperAdmin() && holdsAdminRole(userId)) {
			throw new com.mugsun.core.tool.exception.ServiceException("无权操作内置管理员账号");
		}
	}

	/** 该用户是否持有内置 admin 角色（中间表无 tenant_id，角色 id 全局唯一，经角色表反查角色码） */
	private boolean holdsAdminRole(Long userId) {
		List<Long> roleIds = userRoleMapper.selectListByQuery(QueryWrapper.create().eq("user_id", userId))
			.stream().map(SysUserRole::getRoleId).toList();
		if (roleIds.isEmpty()) {
			return false;
		}
		return roleMapper.selectListByIds(roleIds).stream()
			.anyMatch(r -> com.mugsun.boot.common.constant.RoleConstants.ADMIN.equals(r.getRoleCode()));
	}
}
