package com.mugsun.boot.log;

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

	public LoginLogService(SysLoginLogMapper loginLogMapper) {
		this.loginLogMapper = loginLogMapper;
	}

	/** 记录一条登录留痕；调用方负责填好用户名、IP、UA 等字段 */
	public void record(SysLoginLog log) {
		TenantContext.ignore(() -> loginLogMapper.insertSelective(log));
	}
}
