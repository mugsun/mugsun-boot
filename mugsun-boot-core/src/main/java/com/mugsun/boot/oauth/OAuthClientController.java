package com.mugsun.boot.oauth;

import cn.dev33.satoken.annotation.SaCheckPermission;
import com.mugsun.boot.oauth.entity.SysOauthClient;
import com.mugsun.boot.oauth.mapper.SysOauthClientMapper;
import com.mugsun.core.tool.api.R;
import com.mugsun.core.tool.exception.ServiceException;
import com.mybatisflex.core.paginate.Page;
import com.mybatisflex.core.query.QueryWrapper;
import org.springframework.web.bind.annotation.*;

import java.security.SecureRandom;

/**
 * OAuth2 客户端管理（开放平台）：CRUD + 启停。
 * 新建返回明文密钥（仅此一次），列表脱敏。
 */
@RestController
@RequestMapping("/system/oauth-client")
@SaCheckPermission("sys:oauth:manage")
public class OAuthClientController {

	private static final String CHARS = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789";
	private static final SecureRandom RANDOM = new SecureRandom();

	private final SysOauthClientMapper clientMapper;

	public OAuthClientController(SysOauthClientMapper clientMapper) {
		this.clientMapper = clientMapper;
	}

	@GetMapping("/page")
	public R<Page<SysOauthClient>> page(@RequestParam(defaultValue = "1") long pageNum,
										@RequestParam(defaultValue = "10") long pageSize) {
		Page<SysOauthClient> page = clientMapper.paginate(pageNum, pageSize,
			QueryWrapper.create().orderBy("id", false));
		page.getRecords().forEach(c -> c.setClientSecret(mask(c.getClientSecret())));
		return R.data(page);
	}

	/** 新建/更新客户端。新建自动生成 clientId/clientSecret 并返回明文密钥（仅此一次） */
	@PostMapping("/save")
	public R<SysOauthClient> save(@RequestBody SysOauthClient param) {
		if (param.getName() == null || param.getName().isBlank()) {
			throw new ServiceException("名称不能为空");
		}
		if (param.getGrantTypes() == null || param.getGrantTypes().isBlank()) {
			throw new ServiceException("授权类型不能为空");
		}
		if (param.getId() == null) {
			SysOauthClient client = new SysOauthClient();
			client.setName(param.getName());
			client.setClientId("mc_" + randomStr(16));
			client.setClientSecret(randomStr(32));
			client.setGrantTypes(param.getGrantTypes());
			client.setScopes(param.getScopes());
			client.setRedirectUri(param.getRedirectUri());
			client.setAccessTokenValidity(param.getAccessTokenValidity() == null ? 7200 : param.getAccessTokenValidity());
			client.setStatus(1);
			client.setRemark(param.getRemark());
			clientMapper.insertSelective(client);
			return R.data(client);
		}
		SysOauthClient client = clientMapper.selectOneById(param.getId());
		if (client == null) {
			throw new ServiceException("客户端不存在");
		}
		client.setName(param.getName());
		client.setGrantTypes(param.getGrantTypes());
		client.setScopes(param.getScopes());
		client.setRedirectUri(param.getRedirectUri());
		if (param.getAccessTokenValidity() != null) {
			client.setAccessTokenValidity(param.getAccessTokenValidity());
		}
		client.setRemark(param.getRemark());
		clientMapper.update(client);
		client.setClientSecret(mask(client.getClientSecret()));
		return R.data(client);
	}

	/** 重置密钥：返回新明文密钥（仅此一次） */
	@PostMapping("/reset-secret/{id}")
	public R<SysOauthClient> resetSecret(@PathVariable Long id) {
		SysOauthClient client = clientMapper.selectOneById(id);
		if (client == null) {
			throw new ServiceException("客户端不存在");
		}
		client.setClientSecret(randomStr(32));
		clientMapper.update(client);
		return R.data(client);
	}

	@PostMapping("/enable/{id}")
	public R<Void> enable(@PathVariable Long id) {
		return setStatus(id, 1, "已启用");
	}

	@PostMapping("/disable/{id}")
	public R<Void> disable(@PathVariable Long id) {
		return setStatus(id, 0, "已停用");
	}

	@PostMapping("/remove/{id}")
	public R<Void> remove(@PathVariable Long id) {
		clientMapper.deleteById(id);
		return R.success("删除成功");
	}

	private R<Void> setStatus(Long id, int status, String msg) {
		SysOauthClient client = clientMapper.selectOneById(id);
		if (client != null) {
			client.setStatus(status);
			clientMapper.update(client);
		}
		return R.success(msg);
	}

	private String mask(String secret) {
		if (secret == null || secret.length() <= 6) {
			return secret;
		}
		return secret.substring(0, 6) + "******";
	}

	private String randomStr(int len) {
		StringBuilder sb = new StringBuilder(len);
		for (int i = 0; i < len; i++) {
			sb.append(CHARS.charAt(RANDOM.nextInt(CHARS.length())));
		}
		return sb.toString();
	}
}
