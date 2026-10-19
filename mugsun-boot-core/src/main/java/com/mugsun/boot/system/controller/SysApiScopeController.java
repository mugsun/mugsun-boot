package com.mugsun.boot.system.controller;

import cn.dev33.satoken.annotation.SaCheckLogin;
import cn.dev33.satoken.annotation.SaCheckPermission;
import com.mugsun.boot.config.ApiScopeRuleLoader;
import com.mugsun.boot.log.OperationLog;
import com.mugsun.boot.system.entity.SysApiScope;
import com.mugsun.boot.system.entity.SysMenu;
import com.mugsun.boot.system.entity.SysRole;
import com.mugsun.boot.system.entity.SysRoleApiScope;
import com.mugsun.boot.system.mapper.SysApiScopeMapper;
import com.mugsun.boot.system.mapper.SysMenuMapper;
import com.mugsun.boot.system.mapper.SysRoleApiScopeMapper;
import com.mugsun.boot.system.mapper.SysRoleMapper;
import com.mugsun.boot.tenant.TenantContext;
import com.mugsun.core.tool.api.R;
import com.mugsun.core.tool.exception.ServiceException;
import com.mybatisflex.core.paginate.Page;
import com.mybatisflex.core.query.QueryWrapper;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * 接口权限：维护路径规则，并把它授给角色。启用后未授权的调用会被拒绝。
 */
@RestController
@RequestMapping("/system/api-scope")
@SaCheckLogin
public class SysApiScopeController {

	private static final Pattern CODE = Pattern.compile("^[A-Za-z0-9_:]{1,64}$");
	private static final Pattern PATH = Pattern.compile("^/[A-Za-z0-9_./-]{1,127}$");
	private static final Set<String> CLOSED = Set.of(
		"/auth/login", "/auth/captcha", "/auth/sm2-public-key", "/auth/sms-login", "/auth/sms-code",
		"/auth/register", "/auth/forget-code", "/auth/forget-reset", "/auth/two-factor",
		"/auth/captcha/config", "/auth/captcha/challenge", "/auth/captcha/verify");

	private final SysApiScopeMapper apiScopeMapper;
	private final SysRoleApiScopeMapper linkMapper;
	private final SysRoleMapper roleMapper;
	private final SysMenuMapper menuMapper;
	private final ApiScopeRuleLoader ruleLoader;

	public SysApiScopeController(SysApiScopeMapper apiScopeMapper, SysRoleApiScopeMapper linkMapper,
								 SysRoleMapper roleMapper, SysMenuMapper menuMapper,
								 ApiScopeRuleLoader ruleLoader) {
		this.apiScopeMapper = apiScopeMapper;
		this.linkMapper = linkMapper;
		this.roleMapper = roleMapper;
		this.menuMapper = menuMapper;
		this.ruleLoader = ruleLoader;
	}

	@GetMapping("/page")
	@SaCheckPermission("sys:api-scope:list")
	public R<Page<SysApiScope>> page(@RequestParam(defaultValue = "1") long pageNum,
									 @RequestParam(defaultValue = "10") long pageSize,
									 @RequestParam(required = false) String name) {
		assertPlatform();
		QueryWrapper query = QueryWrapper.create().orderBy("id", true);
		if (name != null && !name.isBlank()) {
			query.like("scope_name", name.trim());
		}
		Page<SysApiScope> result = apiScopeMapper.paginate(pageNum, pageSize, query);
		fillGrantCount(result.getRecords());
		return R.data(result);
	}

	@GetMapping("/roles")
	@SaCheckPermission("sys:api-scope:grant")
	public R<Map<String, Object>> roles(@RequestParam Long id) {
		assertPlatform();
		require(id);
		List<SysRole> roles = roleMapper.selectListByQuery(QueryWrapper.create().orderBy("sort", true).orderBy("id", true));
		Set<Long> roleIds = roles.stream().map(SysRole::getId).collect(Collectors.toSet());
		List<String> checked = linkMapper.selectListByQuery(QueryWrapper.create().eq("api_scope_id", id)).stream()
			.map(SysRoleApiScope::getRoleId)
			.filter(roleIds::contains)
			.map(String::valueOf)
			.toList();
		List<Map<String, Object>> options = new ArrayList<>();
		for (SysRole role : roles) {
			Map<String, Object> row = new LinkedHashMap<>();
			row.put("id", String.valueOf(role.getId()));
			row.put("roleName", role.getRoleName());
			row.put("roleCode", role.getRoleCode());
			options.add(row);
		}
		Map<String, Object> data = new LinkedHashMap<>();
		data.put("roles", options);
		data.put("checked", checked);
		return R.data(data);
	}

	@PostMapping("/submit")
	@SaCheckPermission("sys:api-scope:save")
	@OperationLog("保存接口权限")
	@Transactional(rollbackFor = Exception.class)
	public R<Void> submit(@RequestBody SysApiScope body) {
		assertPlatform();
		String name = body.getScopeName() == null ? "" : body.getScopeName().trim();
		String code = body.getResourceCode() == null ? "" : body.getResourceCode().trim();
		String path = normalizePath(body.getScopePath());
		if (name.isEmpty()) {
			throw new ServiceException("请填写权限名称");
		}
		if (name.length() > 64) {
			throw new ServiceException("权限名称请控制在 64 字以内");
		}
		if (code.isEmpty()) {
			throw new ServiceException("请填写权限编码");
		}
		if (!CODE.matcher(code).matches()) {
			throw new ServiceException("编码请使用字母、数字、下划线或冒号，最长 64 位");
		}
		assertPath(path);
		int scopeType = body.getScopeType() == null ? 1 : body.getScopeType();
		if (scopeType != 1 && scopeType != 2) {
			throw new ServiceException("接口类型请选择系统接口或业务接口");
		}
		int status = body.getStatus() == null ? 1 : body.getStatus();
		if (status != 0 && status != 1) {
			throw new ServiceException("状态只能是启用或停用");
		}
		String remark = body.getRemark() == null ? null : body.getRemark().trim();
		if (remark != null && remark.length() > 255) {
			throw new ServiceException("备注请控制在 255 字以内");
		}
		if (body.getMenuId() != null && menuMapper.selectOneById(body.getMenuId()) == null) {
			throw new ServiceException("关联菜单不存在或已删除，请重新选择");
		}
		assertCodeFree(code, body.getId());
		assertPathFree(path, body.getId());
		if (body.getId() == null) {
			SysApiScope row = new SysApiScope();
			fill(row, name, code, path, scopeType, status, remark, body.getMenuId());
			apiScopeMapper.insert(row);
		} else {
			SysApiScope row = require(body.getId());
			fill(row, name, code, path, scopeType, status, remark, body.getMenuId());
			apiScopeMapper.update(row);
		}
		ruleLoader.reload();
		if (status == 1) {
			return R.success("已启用。除平台管理员外，只有被授权的角色可以调用 " + path);
		}
		return R.success("已停用。该路径不再额外校验接口权限");
	}

	@PostMapping("/grant")
	@SaCheckPermission("sys:api-scope:grant")
	@OperationLog("授权接口权限")
	@Transactional(rollbackFor = Exception.class)
	public R<Void> grant(@RequestBody GrantBody body) {
		assertPlatform();
		if (body == null || body.id() == null) {
			throw new ServiceException("请选择要授权的接口权限");
		}
		SysApiScope row = require(body.id());
		List<Long> roleIds = body.roleIds() == null ? List.of() : body.roleIds();
		List<SysRole> tenantRoles = roleMapper.selectListByQuery(QueryWrapper.create());
		Set<Long> tenantRoleIds = tenantRoles.stream().map(SysRole::getId).collect(Collectors.toSet());
		for (Long roleId : roleIds) {
			if (roleId == null || !tenantRoleIds.contains(roleId)) {
				throw new ServiceException("所选角色不存在或已删除，请重新勾选");
			}
		}
		List<SysRoleApiScope> existing = linkMapper.selectListByQuery(QueryWrapper.create().eq("api_scope_id", row.getId()));
		for (SysRoleApiScope link : existing) {
			if (tenantRoleIds.contains(link.getRoleId())) {
				linkMapper.deleteById(link.getId());
			}
		}
		for (Long roleId : roleIds) {
			SysRoleApiScope link = new SysRoleApiScope();
			link.setRoleId(roleId);
			link.setApiScopeId(row.getId());
			linkMapper.insert(link);
		}
		ruleLoader.reload();
		if (roleIds.isEmpty()) {
			return R.success("已清空授权。除平台管理员外，调用 " + row.getScopePath() + " 会被拒绝");
		}
		return R.success("已授权 " + roleIds.size() + " 个角色。其他角色调用 " + row.getScopePath() + " 会被拒绝");
	}

	@PostMapping("/remove")
	@SaCheckPermission("sys:api-scope:remove")
	@OperationLog("删除接口权限")
	@Transactional(rollbackFor = Exception.class)
	public R<Void> remove(@RequestBody List<Long> ids) {
		assertPlatform();
		if (ids == null || ids.isEmpty()) {
			throw new ServiceException("请选择要删除的接口权限");
		}
		for (Long id : ids) {
			if (id == null) {
				continue;
			}
			SysApiScope row = apiScopeMapper.selectOneById(id);
			if (row == null) {
				throw new ServiceException("接口权限不存在或已删除，请刷新后再试");
			}
			List<SysRoleApiScope> links = linkMapper.selectListByQuery(QueryWrapper.create().eq("api_scope_id", id));
			for (SysRoleApiScope link : links) {
				linkMapper.deleteById(link.getId());
			}
			apiScopeMapper.deleteById(id);
		}
		ruleLoader.reload();
		return R.success("已删除。该路径恢复为原来的访问方式");
	}

	private void fillGrantCount(List<SysApiScope> rows) {
		if (rows == null || rows.isEmpty()) {
			return;
		}
		Set<Long> tenantRoleIds = roleMapper.selectListByQuery(QueryWrapper.create()).stream()
			.map(SysRole::getId).collect(Collectors.toSet());
		List<Long> ids = rows.stream().map(SysApiScope::getId).toList();
		Map<Long, Long> counts = linkMapper.selectListByQuery(QueryWrapper.create().in("api_scope_id", ids)).stream()
			.filter(link -> tenantRoleIds.contains(link.getRoleId()))
			.collect(Collectors.groupingBy(SysRoleApiScope::getApiScopeId, Collectors.counting()));
		for (SysApiScope row : rows) {
			row.setGrantCount(counts.getOrDefault(row.getId(), 0L).intValue());
		}
	}

	private void fill(SysApiScope row, String name, String code, String path, int scopeType, int status,
					  String remark, Long menuId) {
		row.setScopeName(name);
		row.setResourceCode(code);
		row.setScopePath(path);
		row.setScopeType(scopeType);
		row.setStatus(status);
		row.setRemark(remark == null || remark.isEmpty() ? null : remark);
		row.setMenuId(menuId);
	}

	private String normalizePath(String raw) {
		if (raw == null) {
			return "";
		}
		String path = raw.trim();
		if (path.length() > 1 && path.endsWith("/")) {
			path = path.substring(0, path.length() - 1);
		}
		return path;
	}

	private void assertPath(String path) {
		if (path.isEmpty()) {
			throw new ServiceException("请填写权限路径");
		}
		if (!PATH.matcher(path).matches() || path.contains("..")) {
			throw new ServiceException("路径需要以 / 开头，只能包含字母、数字、下划线、点和连字符");
		}
		if (path.substring(1).indexOf('/') < 0) {
			throw new ServiceException("路径至少要包含两段，例如 /system/user/page，避免一次拦住整组接口");
		}
		for (String closed : CLOSED) {
			if (path.equals(closed) || closed.startsWith(path + "/")) {
				throw new ServiceException("登录、验证码和注册接口不能纳入接口权限，否则无人能进入系统");
			}
		}
	}

	private void assertCodeFree(String code, Long id) {
		SysApiScope exist = apiScopeMapper.selectOneByQuery(QueryWrapper.create().eq("resource_code", code));
		if (exist != null && (id == null || !exist.getId().equals(id))) {
			throw new ServiceException("权限编码已存在，请换一个");
		}
	}

	private void assertPathFree(String path, Long id) {
		SysApiScope exist = apiScopeMapper.selectOneByQuery(QueryWrapper.create().eq("scope_path", path));
		if (exist != null && (id == null || !exist.getId().equals(id))) {
			throw new ServiceException("路径已被规则「" + exist.getScopeName() + "」使用，请换一个路径或编辑原规则");
		}
	}

	private SysApiScope require(Long id) {
		SysApiScope row = apiScopeMapper.selectOneById(id);
		if (row == null) {
			throw new ServiceException("接口权限不存在或已删除，请刷新后再试");
		}
		return row;
	}

	private void assertPlatform() {
		if (!TenantContext.isPlatformSuperAdmin()) {
			throw new ServiceException("接口权限只允许平台管理员维护。请使用平台管理员账号操作");
		}
	}

	public record GrantBody(Long id, List<Long> roleIds) {
	}
}
