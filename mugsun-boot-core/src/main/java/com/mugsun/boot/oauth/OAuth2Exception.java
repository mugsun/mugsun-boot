package com.mugsun.boot.oauth;

/**
 * OAuth2 协议错误：承载 RFC6749 标准 error 码 + 描述 + HTTP 状态，
 * 由 OAuthController 本地异常处理器转为标准 {@code {"error","error_description"}} 响应。
 */
public class OAuth2Exception extends RuntimeException {

	private final String error;
	private final int status;

	public OAuth2Exception(String error, String description, int status) {
		super(description);
		this.error = error;
		this.status = status;
	}

	/** invalid_request 400 */
	public static OAuth2Exception invalidRequest(String desc) {
		return new OAuth2Exception("invalid_request", desc, 400);
	}

	/** invalid_client 401 */
	public static OAuth2Exception invalidClient(String desc) {
		return new OAuth2Exception("invalid_client", desc, 401);
	}

	/** invalid_grant 400 */
	public static OAuth2Exception invalidGrant(String desc) {
		return new OAuth2Exception("invalid_grant", desc, 400);
	}

	/** unsupported_grant_type 400 */
	public static OAuth2Exception unsupportedGrantType(String desc) {
		return new OAuth2Exception("unsupported_grant_type", desc, 400);
	}

	/** invalid_scope 400 */
	public static OAuth2Exception invalidScope(String desc) {
		return new OAuth2Exception("invalid_scope", desc, 400);
	}

	public String getError() {
		return error;
	}

	public int getStatus() {
		return status;
	}
}
