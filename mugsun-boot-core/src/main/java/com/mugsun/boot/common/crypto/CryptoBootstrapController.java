package com.mugsun.boot.common.crypto;

import cn.dev33.satoken.annotation.SaCheckLogin;
import cn.dev33.satoken.stp.StpUtil;
import com.mugsun.boot.tenant.TenantContext;
import com.mugsun.core.tool.api.R;
import com.mugsun.core.tool.exception.ForbiddenException;
import com.mugsun.core.web.crypto.ApiCryptoService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 接口传输密钥启动下发：前端运行时拿到与后端同一把 SM4 传输密钥，用户什么都不用配。
 * <ul>
 *   <li>只对已登录会话下发（{@link SaCheckLogin}，不在匿名白名单），会话必须带租户；</li>
 *   <li>按用户限流（{@link CryptoBootstrapRateLimiter}），响应 {@code Cache-Control: no-store}；</li>
 *   <li>只返回传输密钥；签名密钥、SM2 私钥、字段加密密钥任何接口都不下发；</li>
 *   <li>日志只记录谁调了，不记录密钥值。</li>
 * </ul>
 * 配了 MUGSUN_API_KEY 时返回的就是配置值（统一成 32 位小写 hex）。
 * 这把密钥只是传输混淆，不能替代 HTTPS。
 */
@RestController
@RequestMapping("/crypto")
@SaCheckLogin
public class CryptoBootstrapController {

	private static final Logger log = LoggerFactory.getLogger(CryptoBootstrapController.class);

	private final ApiCryptoService apiCryptoService;
	private final CryptoBootstrapRateLimiter rateLimiter;

	public CryptoBootstrapController(ApiCryptoService apiCryptoService, CryptoBootstrapRateLimiter rateLimiter) {
		this.apiCryptoService = apiCryptoService;
		this.rateLimiter = rateLimiter;
	}

	@GetMapping("/bootstrap")
	public ResponseEntity<R<Map<String, Object>>> bootstrap() {
		String userId = StpUtil.getLoginIdAsString();
		Object t = StpUtil.getSession().get(TenantContext.TENANT_SESSION_KEY);
		String tenantId = t == null ? null : t.toString();
		if (tenantId == null || tenantId.isBlank()) {
			throw new ForbiddenException("会话没有租户，不下发接口密钥");
		}
		if (!rateLimiter.tryAcquire(userId)) {
			log.warn("接口密钥启动下发被限流：userId={} tenantId={}", userId, tenantId);
			return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS).cacheControl(CacheControl.noStore())
				.body(R.fail("请求过于频繁，请稍后再试"));
		}
		log.info("接口密钥启动下发：userId={} tenantId={}", userId, tenantId);
		Map<String, Object> data = new LinkedHashMap<>();
		data.put("algorithm", "SM4-GCM");
		data.put("keyHex", apiCryptoService.transportKeyHex());
		data.put("keySource", apiCryptoService.keySource() == ApiCryptoService.KeySource.CONFIGURED
			? "configured" : "auto-generated");
		return ResponseEntity.ok().cacheControl(CacheControl.noStore()).header("Pragma", "no-cache")
			.body(R.data(data));
	}
}
