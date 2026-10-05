package com.mugsun.boot.oauth;

import cn.dev33.satoken.annotation.SaCheckPermission;
import com.mugsun.boot.common.repeat.RepeatSubmit;
import com.mugsun.boot.oauth.entity.SysOauthClient;
import com.mugsun.boot.oauth.mapper.SysOauthClientMapper;
import com.mugsun.core.tool.api.R;
import com.mugsun.core.tool.exception.ServiceException;
import com.mybatisflex.core.paginate.Page;
import com.mybatisflex.core.query.QueryWrapper;
import org.springframework.web.bind.annotation.*;

import java.security.SecureRandom;
import java.util.LinkedHashSet;
import java.util.Set;

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
	private static final Set<String> GRANTS = Set.of(
		OAuthService.GRANT_CLIENT_CREDENTIALS,
		OAuthService.GRANT_AUTHORIZATION_CODE,
		OAuthService.GRANT_REFRESH_TOKEN);

	private final SysOauthClientMapper clientMapper;

	public OAuthClientController(SysOauthClientMapper clientMapper) {
		this.clientMapper = clientMapper;
	}

	@GetMapping("/page")
	public R<Page<SysOauthClient>> page(@RequestParam(defaultValue = "1") long pageNum,
										@RequestParam(defaultValue = "10") long pageSize,
										@RequestParam(required = false) String name) {
		QueryWrapper query = QueryWrapper.create().orderBy("id", false);
		if (name != null && !name.isBlank()) {
			query.like("name", name.trim());
		}
		Page<SysOauthClient> page = clientMapper.paginate(pageNum, pageSize, query);
		page.getRecords().forEach(c -> c.setClientSecret(mask(c.getClientSecret())));
		return R.data(page);
	}

	/** 新建/更新客户端。新建自动生成 clientId/clientSecret 并返回明文密钥（仅此一次） */
	@RepeatSubmit
	@PostMapping("/save")
	public R<SysOauthClient> save(@RequestBody SysOauthClient param) {
		if (param == null) {
			throw new ServiceException("请填写名称并选择授权类型后再保存");
		}
		normalize(param);
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
			throw new ServiceException("这个客户端不存在，或属于其他租户。请刷新列表后再编辑");
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
	@RepeatSubmit
	@PostMapping("/reset-secret/{id}")
	public R<SysOauthClient> resetSecret(@PathVariable Long id) {
		SysOauthClient client = clientMapper.selectOneById(id);
		if (client == null) {
			throw new ServiceException("这个客户端不存在，或属于其他租户。请刷新列表后再重置密钥");
		}
		client.setClientSecret(randomStr(32));
		clientMapper.update(client);
		return R.data(client);
	}

	@PostMapping("/enable/{id}")
	public R<Void> enable(@PathVariable Long id) {
		return setStatus(id, 1, "已启用。这个客户端可以继续换取访问令牌");
	}

	@PostMapping("/disable/{id}")
	public R<Void> disable(@PathVariable Long id) {
		return setStatus(id, 0, "已停用。这个客户端不能再换取新令牌，请先启用再试");
	}

	@RepeatSubmit
	@PostMapping("/remove/{id}")
	public R<Void> remove(@PathVariable Long id) {
		SysOauthClient client = clientMapper.selectOneById(id);
		if (client == null) {
			throw new ServiceException("这个客户端不存在，或属于其他租户。请刷新列表后再删");
		}
		clientMapper.deleteById(id);
		return R.success("已删除「" + client.getName() + "」。列表里不会再出现，旧密钥也不能再换令牌");
	}

	private R<Void> setStatus(Long id, int status, String msg) {
		SysOauthClient client = clientMapper.selectOneById(id);
		if (client == null) {
			throw new ServiceException("这个客户端不存在，或属于其他租户。请刷新列表后再改状态");
		}
		client.setStatus(status);
		clientMapper.update(client);
		return R.success(msg);
	}

	private void normalize(SysOauthClient param) {
		String name = param.getName() == null ? "" : param.getName().trim();
		if (name.isEmpty()) {
			throw new ServiceException("请填写客户端名称");
		}
		if (name.length() > 64) {
			throw new ServiceException("客户端名称请控制在 64 个字以内");
		}
		param.setName(name);
		QueryWrapper duplicate = QueryWrapper.create().eq("name", name);
		if (param.getId() != null) {
			duplicate.ne("id", param.getId());
		}
		if (clientMapper.selectCountByQuery(duplicate) > 0) {
			throw new ServiceException("已经有叫「" + name + "」的客户端。请换一个名称，或编辑原来那个");
		}
		String rawGrants = param.getGrantTypes() == null ? "" : param.getGrantTypes();
		LinkedHashSet<String> grants = new LinkedHashSet<>();
		for (String part : rawGrants.split(",")) {
			String grant = part.trim();
			if (grant.isEmpty()) {
				continue;
			}
			if (!GRANTS.contains(grant)) {
				throw new ServiceException("授权类型只能选择客户端凭证、授权码或刷新令牌");
			}
			grants.add(grant);
		}
		if (!grants.contains(OAuthService.GRANT_CLIENT_CREDENTIALS)
			&& !grants.contains(OAuthService.GRANT_AUTHORIZATION_CODE)) {
			throw new ServiceException("请至少选择客户端凭证或授权码。刷新令牌需要搭配其中一种");
		}
		param.setGrantTypes(String.join(",", grants));
		String redirect = param.getRedirectUri() == null ? "" : param.getRedirectUri().trim();
		boolean authCode = grants.contains(OAuthService.GRANT_AUTHORIZATION_CODE);
		if (authCode && redirect.isEmpty()) {
			throw new ServiceException("选择授权码时，请填写回调地址，并以 http:// 或 https:// 开头");
		}
		if (!redirect.isEmpty()) {
			if (redirect.length() > 255 || redirect.chars().anyMatch(Character::isWhitespace)
				|| !(redirect.startsWith("http://") || redirect.startsWith("https://"))) {
				throw new ServiceException("回调地址要以 http:// 或 https:// 开头，且不能包含空格");
			}
		}
		param.setRedirectUri(redirect.isEmpty() ? null : redirect);
		String rawScopes = param.getScopes() == null ? "" : param.getScopes();
		LinkedHashSet<String> scopes = new LinkedHashSet<>();
		for (String part : rawScopes.split(",")) {
			String scope = part.trim();
			if (scope.isEmpty()) {
				continue;
			}
			if (!scope.matches("^[A-Za-z][A-Za-z0-9_.:-]{0,63}$")) {
				throw new ServiceException("授权范围请用字母开头，只能包含字母、数字、下划线、点、冒号和中横线");
			}
			scopes.add(scope);
		}
		String joinedScopes = String.join(",", scopes);
		if (joinedScopes.length() > 255) {
			throw new ServiceException("授权范围过长。请减少一些后再保存");
		}
		param.setScopes(joinedScopes.isEmpty() ? null : joinedScopes);
		if (param.getAccessTokenValidity() == null) {
			param.setAccessTokenValidity(7200);
		} else if (param.getAccessTokenValidity() < 60 || param.getAccessTokenValidity() > 2592000) {
			throw new ServiceException("令牌有效期请填 60 秒到 30 天之间的秒数");
		}
		String remark = param.getRemark() == null ? "" : param.getRemark().trim();
		if (remark.length() > 255) {
			throw new ServiceException("备注请控制在 255 个字以内");
		}
		param.setRemark(remark.isEmpty() ? null : remark);
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
