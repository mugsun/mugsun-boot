package com.mugsun.boot.oauth;

import cn.dev33.satoken.annotation.SaCheckLogin;
import cn.dev33.satoken.stp.StpUtil;
import com.mugsun.boot.common.config.WebProperties;
import com.mugsun.boot.oauth.entity.SysOauthClient;
import com.mugsun.core.tool.api.R;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * OAuth2 授权服务器端点（对齐 RFC6749/7636/7662/7009，公共访问）。
 * 令牌/自省/撤销端点收 form 入参、返回标准原始 JSON（非 R 信封）、支持 HTTP Basic 客户端认证；
 * 授权端点 302 到前端同意页。异常统一转 {@code {"error","error_description"}}。
 */
@RestController
@RequestMapping("/oauth2")
public class OAuthController {

	private final OAuthService oauthService;

	private final WebProperties webProperties;

	public OAuthController(OAuthService oauthService, WebProperties webProperties) {
		this.oauthService = oauthService;
		this.webProperties = webProperties;
	}

	/** 令牌端点：grant_type=authorization_code / refresh_token / client_credentials，form 入参，标准 JSON 响应 */
	@PostMapping(value = "/token", consumes = MediaType.APPLICATION_FORM_URLENCODED_VALUE)
	public ResponseEntity<Map<String, Object>> token(
		@RequestParam(value = "grant_type", required = false) String grantType,
		@RequestParam(value = "client_id", required = false) String clientId,
		@RequestParam(value = "client_secret", required = false) String clientSecret,
		@RequestParam(value = "scope", required = false) String scope,
		@RequestParam(value = "code", required = false) String code,
		@RequestParam(value = "redirect_uri", required = false) String redirectUri,
		@RequestParam(value = "refresh_token", required = false) String refreshToken,
		@RequestParam(value = "code_verifier", required = false) String codeVerifier,
		@RequestHeader(value = "Authorization", required = false) String authHeader) {

		String[] cred = resolveClientCredentials(authHeader, clientId, clientSecret);
		OAuthService.TokenResult result = switch (grantType == null ? "" : grantType) {
			case OAuthService.GRANT_CLIENT_CREDENTIALS -> oauthService.clientCredentials(cred[0], cred[1], scope);
			case OAuthService.GRANT_AUTHORIZATION_CODE -> oauthService.authorizationCode(cred[0], cred[1], code, redirectUri, codeVerifier);
			case OAuthService.GRANT_REFRESH_TOKEN -> oauthService.refreshToken(cred[0], cred[1], refreshToken);
			default -> throw OAuth2Exception.unsupportedGrantType("不支持的授权类型：" + grantType);
		};
		Map<String, Object> resp = new LinkedHashMap<>();
		resp.put("access_token", result.accessToken());
		resp.put("token_type", "Bearer");
		resp.put("expires_in", result.expiresIn());
		if (result.refreshToken() != null) {
			resp.put("refresh_token", result.refreshToken());
		}
		resp.put("scope", result.scope());
		return ResponseEntity.ok(resp);
	}

	/** 令牌自省（RFC7662）：客户端认证后返回令牌状态 */
	@PostMapping(value = "/introspect", consumes = MediaType.APPLICATION_FORM_URLENCODED_VALUE)
	public ResponseEntity<Map<String, Object>> introspect(
		@RequestParam("token") String token,
		@RequestParam(value = "client_id", required = false) String clientId,
		@RequestParam(value = "client_secret", required = false) String clientSecret,
		@RequestHeader(value = "Authorization", required = false) String authHeader) {
		String[] cred = resolveClientCredentials(authHeader, clientId, clientSecret);
		oauthService.authenticateClient(cred[0], cred[1]);
		return ResponseEntity.ok(oauthService.introspect(token));
	}

	/** 令牌撤销（RFC7009）：客户端认证后撤销，幂等返回 200 */
	@PostMapping(value = "/revoke", consumes = MediaType.APPLICATION_FORM_URLENCODED_VALUE)
	public ResponseEntity<Map<String, Object>> revoke(
		@RequestParam("token") String token,
		@RequestParam(value = "client_id", required = false) String clientId,
		@RequestParam(value = "client_secret", required = false) String clientSecret,
		@RequestHeader(value = "Authorization", required = false) String authHeader) {
		String[] cred = resolveClientCredentials(authHeader, clientId, clientSecret);
		oauthService.authenticateClient(cred[0], cred[1]);
		oauthService.revoke(token);
		return ResponseEntity.ok(Map.of());
	}

	/** 授权端点（RFC6749 §4.1）：校验 response_type/client 后 302 到前端同意页（登录校验由同意页承担） */
	@GetMapping("/authorize")
	public void authorize(
		@RequestParam("response_type") String responseType,
		@RequestParam("client_id") String clientId,
		@RequestParam(value = "redirect_uri", required = false) String redirectUri,
		@RequestParam(value = "scope", required = false) String scope,
		@RequestParam(value = "state", required = false) String state,
		@RequestParam(value = "code_challenge", required = false) String codeChallenge,
		@RequestParam(value = "code_challenge_method", required = false) String codeChallengeMethod,
		HttpServletResponse response) throws IOException {
		if (!"code".equals(responseType)) {
			throw OAuth2Exception.invalidRequest("仅支持 response_type=code");
		}
		oauthService.loadEnabledClient(clientId);
		StringBuilder url = new StringBuilder(webProperties.oauthConsentPage()).append("?client_id=").append(enc(clientId));
		appendParam(url, "redirect_uri", redirectUri);
		appendParam(url, "scope", scope);
		appendParam(url, "state", state);
		appendParam(url, "code_challenge", codeChallenge);
		appendParam(url, "code_challenge_method", codeChallengeMethod);
		response.sendRedirect(url.toString());
	}

	/** 同意页数据（SPA 内部，R 信封）：当前登录用户查看客户端名称与请求范围 */
	@GetMapping("/authorize/info")
	@SaCheckLogin
	public R<Map<String, Object>> authorizeInfo(
		@RequestParam("client_id") String clientId,
		@RequestParam(value = "scope", required = false) String scope) {
		try {
			SysOauthClient client = oauthService.loadEnabledClient(clientId);
			Map<String, Object> resp = new LinkedHashMap<>();
			resp.put("clientId", client.getClientId());
			resp.put("clientName", client.getName());
			resp.put("scopes", oauthService.clientScopes(client));
			return R.data(resp);
		} catch (OAuth2Exception e) {
			return R.fail(e.getMessage());
		}
	}

	/** 同意确认（SPA 内部，R 信封）：登录用户批准后颁发一次性授权码 */
	@PostMapping("/authorize/confirm")
	@SaCheckLogin
	public R<Map<String, Object>> authorizeConfirm(@RequestBody Map<String, String> body) {
		try {
			String code = oauthService.issueCode(StpUtil.getLoginIdAsLong(),
				body.get("clientId"), body.get("scope"), body.get("redirectUri"),
				body.get("codeChallenge"), body.get("codeChallengeMethod"));
			Map<String, Object> resp = new LinkedHashMap<>();
			resp.put("code", code);
			resp.put("redirectUri", body.get("redirectUri"));
			resp.put("state", body.get("state"));
			return R.data(resp);
		} catch (OAuth2Exception e) {
			return R.fail(e.getMessage());
		}
	}

	/** 客户端认证：优先 HTTP Basic，回退 form 参数 */
	private String[] resolveClientCredentials(String authHeader, String bodyClientId, String bodyClientSecret) {
		if (authHeader != null && authHeader.regionMatches(true, 0, "Basic ", 0, 6)) {
			String decoded;
			try {
				decoded = new String(Base64.getDecoder().decode(authHeader.substring(6).trim()), StandardCharsets.UTF_8);
			} catch (IllegalArgumentException e) {
				throw OAuth2Exception.invalidClient("Basic 凭证解码失败");
			}
			int i = decoded.indexOf(':');
			if (i < 0) {
				throw OAuth2Exception.invalidClient("Basic 凭证格式错误");
			}
			return new String[]{decoded.substring(0, i), decoded.substring(i + 1)};
		}
		return new String[]{bodyClientId, bodyClientSecret};
	}

	private void appendParam(StringBuilder url, String name, String value) {
		if (value != null && !value.isBlank()) {
			url.append('&').append(name).append('=').append(enc(value));
		}
	}

	private String enc(String v) {
		return URLEncoder.encode(v, StandardCharsets.UTF_8);
	}

	/** 协议错误统一转标准 OAuth2 错误响应 {error, error_description} */
	@ExceptionHandler(OAuth2Exception.class)
	public ResponseEntity<Map<String, Object>> handleOAuth2(OAuth2Exception e) {
		Map<String, Object> body = new LinkedHashMap<>();
		body.put("error", e.getError());
		body.put("error_description", e.getMessage());
		return ResponseEntity.status(e.getStatus()).body(body);
	}
}
