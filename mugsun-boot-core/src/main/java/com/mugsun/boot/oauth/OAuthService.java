package com.mugsun.boot.oauth;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mugsun.boot.oauth.entity.SysOauthClient;
import com.mugsun.boot.oauth.mapper.SysOauthClientMapper;
import com.mybatisflex.core.query.QueryWrapper;
import com.mugsun.boot.tenant.TenantContext;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.Arrays;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * OAuth2 授权服务（对齐 RFC6749/7636/7662/7009）：客户端凭证 / 授权码(+PKCE) / 刷新令牌 三种模式，
 * 令牌自省与撤销。令牌为不透明串，元数据（clientId + scope + tenantId + userId）随令牌缓存 Redis(TTL)，
 * tenantId 供 {@link OpenApiInterceptor} 对 /open/** 施加租户隔离。抛 {@link OAuth2Exception} 标准错误。
 */
@Service
public class OAuthService {

	public static final String GRANT_CLIENT_CREDENTIALS = "client_credentials";
	public static final String GRANT_AUTHORIZATION_CODE = "authorization_code";
	public static final String GRANT_REFRESH_TOKEN = "refresh_token";

	private static final String TOKEN_PREFIX = "mugsun:oauth:token:";
	private static final String REFRESH_PREFIX = "mugsun:oauth:refresh:";
	private static final String CODE_PREFIX = "mugsun:oauth:code:";
	private static final String CHARS = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789";
	private static final SecureRandom RANDOM = new SecureRandom();
	private static final long CODE_TTL = 300;
	private static final long REFRESH_TTL = 2592000;

	private final SysOauthClientMapper clientMapper;
	private final StringRedisTemplate redisTemplate;
	private final ObjectMapper objectMapper;

	public OAuthService(SysOauthClientMapper clientMapper, StringRedisTemplate redisTemplate, ObjectMapper objectMapper) {
		this.clientMapper = clientMapper;
		this.redisTemplate = redisTemplate;
		this.objectMapper = objectMapper;
	}

	/** 按 clientId 查启用客户端（公共令牌端点无租户上下文，跳过隔离；不存在/停用抛 invalid_client） */
	public SysOauthClient loadEnabledClient(String clientId) {
		if (clientId == null || clientId.isBlank()) {
			throw OAuth2Exception.invalidClient("client_id 不能为空");
		}
		SysOauthClient client = TenantContext.ignore(() ->
			clientMapper.selectOneByQuery(QueryWrapper.create().eq("client_id", clientId)));
		if (client == null) {
			throw OAuth2Exception.invalidClient("客户端不存在");
		}
		if (!Integer.valueOf(1).equals(client.getStatus())) {
			throw OAuth2Exception.invalidClient("客户端已停用");
		}
		return client;
	}

	/** 客户端认证：校验密钥（客户端凭证/授权码/刷新令牌换 token 均需） */
	public SysOauthClient authenticateClient(String clientId, String clientSecret) {
		SysOauthClient client = loadEnabledClient(clientId);
		if (client.getClientSecret() == null || !client.getClientSecret().equals(clientSecret)) {
			throw OAuth2Exception.invalidClient("客户端密钥错误");
		}
		return client;
	}

	/** 客户端凭证模式（不发 refresh_token，符合 RFC6749 §4.4.3） */
	public TokenResult clientCredentials(String clientId, String clientSecret, String requestScope) {
		SysOauthClient client = authenticateClient(clientId, clientSecret);
		assertGrantAllowed(client, GRANT_CLIENT_CREDENTIALS);
		String scope = resolveScope(client, requestScope);
		return issueToken(client, scope, null, false);
	}

	/** 授权码颁发：登录用户授权后生成一次性 code，记录 redirect_uri 与 PKCE 挑战供换 token 时校验 */
	public String issueCode(long userId, String clientId, String requestScope, String redirectUri,
							String codeChallenge, String codeChallengeMethod) {
		SysOauthClient client = loadEnabledClient(clientId);
		assertGrantAllowed(client, GRANT_AUTHORIZATION_CODE);
		validateRedirectUri(client, redirectUri);
		String scope = resolveScope(client, requestScope);
		String code = randomStr(32);
		Map<String, String> meta = new LinkedHashMap<>();
		meta.put("clientId", clientId);
		meta.put("scope", scope);
		meta.put("userId", String.valueOf(userId));
		meta.put("tenantId", nullToEmpty(client.getTenantId()));
		meta.put("redirectUri", nullToEmpty(redirectUri));
		meta.put("codeChallenge", nullToEmpty(codeChallenge));
		meta.put("codeChallengeMethod", codeChallenge == null || codeChallenge.isBlank() ? ""
			: (codeChallengeMethod == null || codeChallengeMethod.isBlank() ? "plain" : codeChallengeMethod));
		redisTemplate.opsForValue().set(CODE_PREFIX + code, writeJson(meta), Duration.ofSeconds(CODE_TTL));
		return code;
	}

	/** 授权码换 token：校验 code 归属 + redirect_uri 一致性 + PKCE，用后即焚，发 refresh_token */
	public TokenResult authorizationCode(String clientId, String clientSecret, String code,
										 String redirectUri, String codeVerifier) {
		SysOauthClient client = authenticateClient(clientId, clientSecret);
		assertGrantAllowed(client, GRANT_AUTHORIZATION_CODE);
		if (code == null || code.isBlank()) {
			throw OAuth2Exception.invalidGrant("授权码不能为空");
		}
		String key = CODE_PREFIX + code;
		String metaJson = redisTemplate.opsForValue().get(key);
		if (metaJson == null) {
			throw OAuth2Exception.invalidGrant("授权码无效或已过期");
		}
		redisTemplate.delete(key);
		Map<String, String> data = readJson(metaJson);
		if (!clientId.equals(data.get("clientId"))) {
			throw OAuth2Exception.invalidGrant("授权码与客户端不匹配");
		}
		String boundRedirect = data.get("redirectUri");
		if (boundRedirect != null && !boundRedirect.isBlank() && !boundRedirect.equals(redirectUri)) {
			throw OAuth2Exception.invalidGrant("redirect_uri 与授权时不一致");
		}
		verifyPkce(data.get("codeChallenge"), data.get("codeChallengeMethod"), codeVerifier);
		return issueToken(client, data.get("scope"), data.get("userId"), true, data.get("tenantId"));
	}

	/** 刷新令牌：校验后轮换（旧 refresh 失效，发新 access + 新 refresh） */
	public TokenResult refreshToken(String clientId, String clientSecret, String refreshToken) {
		SysOauthClient client = authenticateClient(clientId, clientSecret);
		assertGrantAllowed(client, GRANT_REFRESH_TOKEN);
		if (refreshToken == null || refreshToken.isBlank()) {
			throw OAuth2Exception.invalidGrant("refresh_token 不能为空");
		}
		String key = REFRESH_PREFIX + refreshToken;
		String metaJson = redisTemplate.opsForValue().get(key);
		if (metaJson == null) {
			throw OAuth2Exception.invalidGrant("refresh_token 无效或已过期");
		}
		Map<String, String> data = readJson(metaJson);
		if (!clientId.equals(data.get("clientId"))) {
			throw OAuth2Exception.invalidGrant("refresh_token 与客户端不匹配");
		}
		redisTemplate.delete(key);
		return issueToken(client, data.get("scope"), data.get("userId"), true, data.get("tenantId"));
	}

	/** 令牌自省（RFC7662）：先查 access，再查 refresh；无效返回 {active:false} */
	public Map<String, Object> introspect(String token) {
		Map<String, Object> result = new LinkedHashMap<>();
		if (token == null || token.isBlank()) {
			result.put("active", false);
			return result;
		}
		String metaJson = redisTemplate.opsForValue().get(TOKEN_PREFIX + token);
		String tokenType = "access_token";
		Long ttl = redisTemplate.getExpire(TOKEN_PREFIX + token, java.util.concurrent.TimeUnit.SECONDS);
		if (metaJson == null) {
			metaJson = redisTemplate.opsForValue().get(REFRESH_PREFIX + token);
			tokenType = "refresh_token";
			ttl = redisTemplate.getExpire(REFRESH_PREFIX + token, java.util.concurrent.TimeUnit.SECONDS);
		}
		if (metaJson == null) {
			result.put("active", false);
			return result;
		}
		Map<String, String> data = readJson(metaJson);
		result.put("active", true);
		result.put("scope", data.getOrDefault("scope", ""));
		result.put("client_id", data.get("clientId"));
		result.put("token_type", tokenType);
		result.put("tenant_id", data.getOrDefault("tenantId", ""));
		if (data.get("userId") != null && !data.get("userId").isBlank()) {
			result.put("sub", data.get("userId"));
		}
		if (ttl != null && ttl > 0) {
			result.put("exp", (System.currentTimeMillis() / 1000) + ttl);
		}
		return result;
	}

	/** 令牌撤销（RFC7009）：access 与 refresh 均尝试删除，幂等 */
	public void revoke(String token) {
		if (token == null || token.isBlank()) {
			return;
		}
		redisTemplate.delete(TOKEN_PREFIX + token);
		redisTemplate.delete(REFRESH_PREFIX + token);
	}

	/** 校验访问令牌，返回元数据（clientId + scope + tenantId）；无效返回 null */
	public TokenInfo resolveToken(String token) {
		if (token == null || token.isBlank()) {
			return null;
		}
		String meta = redisTemplate.opsForValue().get(TOKEN_PREFIX + token);
		if (meta == null) {
			return null;
		}
		Map<String, String> data = readJson(meta);
		return new TokenInfo(data.get("clientId"), toScopeSet(data.get("scope")), data.get("tenantId"));
	}

	private TokenResult issueToken(SysOauthClient client, String scope, String userId, boolean withRefresh) {
		return issueToken(client, scope, userId, withRefresh, client.getTenantId());
	}

	private TokenResult issueToken(SysOauthClient client, String scope, String userId, boolean withRefresh, String tenantId) {
		String token = "mo_" + randomStr(40);
		int validity = client.getAccessTokenValidity() == null ? 7200 : client.getAccessTokenValidity();
		Map<String, String> meta = new LinkedHashMap<>();
		meta.put("clientId", client.getClientId());
		meta.put("scope", scope == null ? "" : scope);
		meta.put("tenantId", nullToEmpty(tenantId));
		meta.put("userId", nullToEmpty(userId));
		redisTemplate.opsForValue().set(TOKEN_PREFIX + token, writeJson(meta), Duration.ofSeconds(validity));
		String refresh = null;
		if (withRefresh) {
			refresh = "mr_" + randomStr(40);
			redisTemplate.opsForValue().set(REFRESH_PREFIX + refresh, writeJson(meta), Duration.ofSeconds(REFRESH_TTL));
		}
		return new TokenResult(token, refresh, scope, validity);
	}

	/** redirect_uri 校验（授权码模式 fail-closed）：客户端必须登记且请求须精确一致——
	 *  未登记的客户端拒绝发码（防任意域跳转钓码 / javascript: URI XSS 盗令牌） */
	private void validateRedirectUri(SysOauthClient client, String redirectUri) {
		if (client.getRedirectUri() == null || client.getRedirectUri().isBlank()) {
			throw OAuth2Exception.invalidRequest("客户端未登记 redirect_uri，禁止授权码模式");
		}
		if (redirectUri == null || redirectUri.isBlank() || !client.getRedirectUri().equals(redirectUri)) {
			throw OAuth2Exception.invalidRequest("redirect_uri 与客户端登记不匹配");
		}
		// 登记值仅允许 http(s)（javascript:/data: 等伪协议一经登记即为 XSS 通道）
		if (!client.getRedirectUri().startsWith("http://") && !client.getRedirectUri().startsWith("https://")) {
			throw OAuth2Exception.invalidRequest("客户端 redirect_uri 协议不合法");
		}
	}

	/** PKCE 校验（RFC7636 S256/plain）：code 登记了挑战则必须携带并匹配 code_verifier */
	private void verifyPkce(String challenge, String method, String verifier) {
		if (challenge == null || challenge.isBlank()) {
			return;
		}
		if (verifier == null || verifier.isBlank()) {
			throw OAuth2Exception.invalidGrant("缺少 code_verifier");
		}
		String computed;
		if ("S256".equalsIgnoreCase(method)) {
			try {
				byte[] digest = MessageDigest.getInstance("SHA-256").digest(verifier.getBytes(StandardCharsets.US_ASCII));
				computed = Base64.getUrlEncoder().withoutPadding().encodeToString(digest);
			} catch (Exception e) {
				throw OAuth2Exception.invalidGrant("PKCE 校验失败");
			}
		} else {
			computed = verifier;
		}
		if (!Objects.equals(computed, challenge)) {
			throw OAuth2Exception.invalidGrant("code_verifier 校验失败");
		}
	}

	private void assertGrantAllowed(SysOauthClient client, String grantType) {
		if (!toScopeSet(client.getGrantTypes()).contains(grantType)) {
			throw OAuth2Exception.unsupportedGrantType("客户端不支持该授权类型：" + grantType);
		}
	}

	/** 请求范围须为客户端可授权范围子集；请求为空则授予全部 */
	private String resolveScope(SysOauthClient client, String requestScope) {
		Set<String> clientScopes = toScopeSet(client.getScopes());
		if (requestScope == null || requestScope.isBlank()) {
			return String.join(",", clientScopes);
		}
		Set<String> request = toScopeSet(requestScope);
		Set<String> invalid = request.stream().filter(s -> !clientScopes.contains(s)).collect(Collectors.toSet());
		if (!invalid.isEmpty()) {
			throw OAuth2Exception.invalidScope("超出客户端授权范围：" + String.join(",", invalid));
		}
		return String.join(",", request);
	}

	/** 供同意页展示：客户端可授权范围集合 */
	public Set<String> clientScopes(SysOauthClient client) {
		return toScopeSet(client.getScopes());
	}

	private Set<String> toScopeSet(String csv) {
		if (csv == null || csv.isBlank()) {
			return new LinkedHashSet<>();
		}
		return Arrays.stream(csv.replace(' ', ',').split(","))
			.map(String::trim).filter(s -> !s.isEmpty())
			.collect(Collectors.toCollection(LinkedHashSet::new));
	}

	private String nullToEmpty(String s) {
		return s == null ? "" : s;
	}

	private String writeJson(Map<String, String> map) {
		try {
			return objectMapper.writeValueAsString(map);
		} catch (Exception e) {
			throw OAuth2Exception.invalidRequest("令牌元数据序列化失败");
		}
	}

	@SuppressWarnings("unchecked")
	private Map<String, String> readJson(String json) {
		try {
			return objectMapper.readValue(json, Map.class);
		} catch (Exception e) {
			throw OAuth2Exception.invalidRequest("令牌元数据解析失败");
		}
	}

	private String randomStr(int len) {
		StringBuilder sb = new StringBuilder(len);
		for (int i = 0; i < len; i++) {
			sb.append(CHARS.charAt(RANDOM.nextInt(CHARS.length())));
		}
		return sb.toString();
	}

	/** 换取令牌结果（refreshToken 可空——客户端凭证模式不发） */
	public record TokenResult(String accessToken, String refreshToken, String scope, int expiresIn) {
	}

	/** 访问令牌元数据 */
	public record TokenInfo(String clientId, Set<String> scopes, String tenantId) {
	}
}
