package com.mugsun.boot.oauth;

import com.mugsun.boot.oauth.entity.SysOauthLog;
import com.mugsun.boot.oauth.mapper.SysOauthLogMapper;
import com.mugsun.boot.tenant.TenantContext;
import org.springframework.stereotype.Service;

/**
 * 开放接口调用日志留痕（平台级，无租户上下文）。
 */
@Service
public class OAuthLogService {

	private final SysOauthLogMapper logMapper;

	public OAuthLogService(SysOauthLogMapper logMapper) {
		this.logMapper = logMapper;
	}

	public void record(String clientId, String apiPath, String scope, int status, String ip, String msg) {
		SysOauthLog log = new SysOauthLog();
		log.setClientId(clientId);
		log.setApiPath(apiPath);
		log.setScope(scope);
		log.setStatus(status);
		log.setIp(ip);
		log.setMsg(msg);
		TenantContext.ignore(() -> logMapper.insertSelective(log));
	}
}
