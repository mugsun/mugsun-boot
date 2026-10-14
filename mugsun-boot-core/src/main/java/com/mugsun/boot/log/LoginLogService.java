package com.mugsun.boot.log;

import com.mugsun.boot.demo.LogDataSourceRouter;

import com.mugsun.boot.log.entity.SysLoginLog;
import com.mugsun.boot.log.mapper.SysLoginLogMapper;
import com.mugsun.boot.tenant.TenantContext;
import org.springframework.stereotype.Service;

/**
 * 登录日志写入。登录发生在租户上下文建立之前，落库时统一忽略租户过滤。
 */
@Service
public class LoginLogService {

	private final SysLoginLogMapper loginLogMapper;
	private final LogDataSourceRouter logRouter;

	public LoginLogService(SysLoginLogMapper loginLogMapper, LogDataSourceRouter logRouter) {
		this.loginLogMapper = loginLogMapper;
		this.logRouter = logRouter;
	}

	/** 记录一条登录留痕；调用方负责填好用户名、IP、UA 等字段 */
	public void record(SysLoginLog log) {
		// demo：走独立日志账号，失败不打断登录（LogDataSourceRouter）
		logRouter.write(() -> {
			TenantContext.ignore(() -> loginLogMapper.insertSelective(log));
		});
	}
}
