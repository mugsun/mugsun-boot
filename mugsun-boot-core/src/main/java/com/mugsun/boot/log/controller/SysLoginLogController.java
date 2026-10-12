package com.mugsun.boot.log.controller;

import cn.dev33.satoken.annotation.SaCheckLogin;
import com.mugsun.boot.auth.LoginLockService;
import com.mugsun.boot.log.OperationLog;
import com.mugsun.boot.log.entity.SysLoginLog;
import com.mugsun.boot.log.mapper.SysLoginLogMapper;
import com.mugsun.core.tool.api.R;
import com.mugsun.core.tool.exception.ServiceException;
import com.mybatisflex.core.paginate.Page;
import com.mybatisflex.core.query.QueryWrapper;
import com.mugsun.boot.tenant.TenantContext;
import org.springframework.web.bind.annotation.*;

import java.util.Map;
import java.util.Set;

/**
 * 登录日志查询（含账号解锁入口：清除登录失败锁定）
 */
@RestController
@RequestMapping("/system/login-log")
@SaCheckLogin
public class SysLoginLogController {

	private static final Set<String> GRANT_TYPES = Set.of("password", "sms", "social", "refresh_token", "captcha");

	private final SysLoginLogMapper loginLogMapper;
	private final LoginLockService loginLockService;

	public SysLoginLogController(SysLoginLogMapper loginLogMapper, LoginLockService loginLockService) {
		this.loginLogMapper = loginLogMapper;
		this.loginLockService = loginLockService;
	}

	@GetMapping("/page")
	@cn.dev33.satoken.annotation.SaCheckPermission("sys:login-log:list")
	public R<Page<SysLoginLog>> page(@RequestParam(defaultValue = "1") long pageNum,
									 @RequestParam(defaultValue = "10") long pageSize,
									 @RequestParam(required = false) String username,
									 @RequestParam(required = false) String ip,
									 @RequestParam(required = false) Integer status,
									 @RequestParam(required = false) String grantType,
									 @RequestParam(required = false) Boolean exact) {
		QueryWrapper query = QueryWrapper.create().orderBy("id", false);
		// 查询条件（值走参数化绑定，LIKE 前后模糊）
		if (username != null && !username.isBlank()) {
			if (Boolean.TRUE.equals(exact)) {
				query.eq("username", username.trim());
			} else {
				query.like("username", username.trim());
			}
		}
		if (ip != null && !ip.isBlank()) {
			query.like("ip", ip.trim());
		}
		if (status != null) {
			query.and("status = ?", status);
		}
		if (grantType != null && !grantType.isBlank()) {
			if (!GRANT_TYPES.contains(grantType.trim())) {
				throw new ServiceException("登录方式请选择密码、短信、社交或刷新令牌");
			}
			query.eq("grant_type", grantType.trim());
		}
		// 平台超管看全平台留痕（ignore）；其余租户仅见本租户登录日志（tenant_id 列自动过滤）
		Page<SysLoginLog> page = TenantContext.isPlatformSuperAdmin()
			? TenantContext.ignore(() -> loginLogMapper.paginate(pageNum, Math.min(pageSize, 500), query))
			: loginLogMapper.paginate(pageNum, Math.min(pageSize, 500), query);
		// 富化锁定标记（账号密码锁与短信 phone 锁两个维度），前端按此行级状态显隐「解锁」按钮
		page.getRecords().forEach(row -> row.setLocked(
			loginLockService.isLocked(loginLockService.keyOf(row.getTenantId(), row.getUsername()))
				|| loginLockService.isLocked(loginLockService.keyOf("phone", row.getUsername()))));
		return R.data(page);
	}

	@GetMapping("/detail")
	@cn.dev33.satoken.annotation.SaCheckPermission("sys:login-log:list")
	public R<SysLoginLog> detail(@RequestParam(required = false) Long id) {
		if (id == null) {
			throw new ServiceException("请从列表中选择一条认证记录后再查看");
		}
		SysLoginLog row = TenantContext.isPlatformSuperAdmin()
			? TenantContext.ignore(() -> loginLogMapper.selectOneById(id))
			: loginLogMapper.selectOneById(id);
		if (row == null) {
			throw new ServiceException("这条认证记录不存在，或属于其他租户。请刷新列表后再看");
		}
		row.setLocked(loginLockService.isLocked(loginLockService.keyOf(row.getTenantId(), row.getUsername()))
			|| loginLockService.isLocked(loginLockService.keyOf("phone", row.getUsername())));
		return R.data(row);
	}

	/**
	 * 解锁账号：按日志行的 租户+账号 维度清除登录失败计数与锁定标记。
	 * 同步清 phone 维度键——短信登录锁键为 phone:手机号，与账号密码锁键（租户:账号）是两个维度。
	 */
	@PostMapping("/unlock")
	@cn.dev33.satoken.annotation.SaCheckPermission("sys:login-log:unlock")
	@OperationLog("解锁账号")
	public R<Void> unlock(@RequestBody Map<String, Long> body) {
		Long id = body.get("id");
		if (id == null) {
			throw new ServiceException("请选择要解锁的登录记录");
		}
		// 行读取与列表同口径：超管跨租户，其余仅本租户（Flex 租户条件自动过滤，越界即 null）
		SysLoginLog row = TenantContext.isPlatformSuperAdmin()
			? TenantContext.ignore(() -> loginLogMapper.selectOneById(id))
			: loginLogMapper.selectOneById(id);
		if (row == null) {
			throw new ServiceException("这条认证记录不存在，或属于其他租户。请刷新列表后再解锁");
		}
		loginLockService.clear(loginLockService.keyOf(row.getTenantId(), row.getUsername()));
		loginLockService.clear(loginLockService.keyOf("phone", row.getUsername()));
		return R.success("解锁成功");
	}
}
