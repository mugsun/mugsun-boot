package com.mugsun.boot.client;

import com.mugsun.boot.client.entity.SysClient;
import com.mugsun.boot.client.mapper.SysClientMapper;
import com.mugsun.boot.common.constant.ClientConstants;
import com.mybatisflex.core.query.QueryWrapper;
import org.springframework.stereotype.Service;

/**
 * 登录客户端策略服务：按 clientId 取差异化策略（验证码开关/并发在线数/令牌有效期）。
 * sys_client 无 tenant_id 列，Flex 不施加租户隔离，登录前无会话即可加载；未配置/停用回退默认策略。
 */
@Service
public class ClientService {

	private final SysClientMapper clientMapper;

	public ClientService(SysClientMapper clientMapper) {
		this.clientMapper = clientMapper;
	}

	public SysClient loadClient(String clientId) {
		String cid = (clientId == null || clientId.isBlank()) ? ClientConstants.DEFAULT_CLIENT_ID : clientId;
		SysClient client = clientMapper.selectOneByQuery(QueryWrapper.create().eq("client_id", cid));
		if (client == null || !Integer.valueOf(1).equals(client.getStatus())) {
			// 未配置或停用：回退默认策略（验证码开、不限在线、30 天）
			SysClient def = new SysClient();
			def.setClientId(cid);
			def.setCaptchaEnabled(1);
			def.setMaxOnline(0);
			def.setTokenTimeout(2592000);
			return def;
		}
		return client;
	}
}
