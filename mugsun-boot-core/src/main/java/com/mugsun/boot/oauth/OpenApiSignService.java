package com.mugsun.boot.oauth;

import cn.hutool.crypto.SecureUtil;
import com.mugsun.boot.oauth.entity.SysOauthClient;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.util.StreamUtils;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

/**
 * 开放接口签名 + 防重放：HMAC-SHA256（client secret 为密钥）+ nonce（Redis 去重）+ timestamp（时间窗）。
 * <p>签名基串 = {@code method\nURI\nquery\ntimestamp\nnonce\nSHA256(body)}；客户端携 X-Timestamp/X-Nonce/X-Sign 头。
 * 篡改任一参与项 → 签名失配拒绝；重放（相同 nonce）→ Redis 命中拒绝；超窗（时钟偏差）→ 拒绝。
 */
@Service
public class OpenApiSignService {

	private static final long WINDOW_SECONDS = 300;
	private static final String NONCE_PREFIX = "openapi:nonce:";

	@Value("${mugsun.open-api.sign-required:true}")
	private boolean signRequired;

	private final StringRedisTemplate redisTemplate;
	private final OAuthService oauthService;

	public OpenApiSignService(StringRedisTemplate redisTemplate, OAuthService oauthService) {
		this.redisTemplate = redisTemplate;
		this.oauthService = oauthService;
	}

	/** 校验签名与防重放；通过返回 null，否则返回拒绝原因。 */
	public String verify(HttpServletRequest request, String clientId) {
		if (!signRequired) {
			return null;
		}
		String timestamp = request.getHeader("X-Timestamp");
		String nonce = request.getHeader("X-Nonce");
		String sign = request.getHeader("X-Sign");
		if (blank(timestamp) || blank(nonce) || blank(sign)) {
			return "缺少签名头（X-Timestamp/X-Nonce/X-Sign）";
		}
		long ts;
		try {
			ts = Long.parseLong(timestamp.trim());
		} catch (NumberFormatException e) {
			return "时间戳非法";
		}
		if (Math.abs(System.currentTimeMillis() - ts) > WINDOW_SECONDS * 1000) {
			return "请求已过期或时钟偏差过大";
		}
		// nonce 防重放：SETNX，TTL 覆盖时间窗两倍，杜绝窗内重放
		Boolean first = redisTemplate.opsForValue().setIfAbsent(
			NONCE_PREFIX + clientId + ":" + nonce, "1", Duration.ofSeconds(WINDOW_SECONDS * 2));
		if (!Boolean.TRUE.equals(first)) {
			return "重放请求（nonce 已使用）";
		}
		SysOauthClient client = oauthService.loadEnabledClient(clientId);
		if (client.getClientSecret() == null || client.getClientSecret().isBlank()) {
			return "客户端未配置密钥，无法验签";
		}
		String expected = SecureUtil.hmacSha256(client.getClientSecret()).digestHex(signBase(request, timestamp, nonce));
		// 恒定时间比较，防签名比对的时序侧信道
		if (!java.security.MessageDigest.isEqual(
			expected.getBytes(StandardCharsets.UTF_8), sign.trim().toLowerCase().getBytes(StandardCharsets.UTF_8))) {
			return "签名校验失败";
		}
		return null;
	}

	/** 签名基串（客户端须以完全一致的口径拼接后 HMAC） */
	private String signBase(HttpServletRequest request, String timestamp, String nonce) {
		String query = request.getQueryString() == null ? "" : request.getQueryString();
		return String.join("\n",
			request.getMethod(), request.getRequestURI(), query, timestamp, nonce, bodyHash(request));
	}

	private String bodyHash(HttpServletRequest request) {
		try {
			byte[] body = StreamUtils.copyToByteArray(request.getInputStream());
			return body.length == 0 ? "" : SecureUtil.sha256(new String(body, StandardCharsets.UTF_8));
		} catch (IOException e) {
			return "";
		}
	}

	private boolean blank(String s) {
		return s == null || s.isBlank();
	}
}
