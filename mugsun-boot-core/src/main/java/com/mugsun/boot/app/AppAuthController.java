package com.mugsun.boot.app;

import cn.dev33.satoken.annotation.SaCheckLogin;
import com.mugsun.boot.app.dto.AppHomeVO;
import com.mugsun.boot.app.dto.AppLoginDTO;
import com.mugsun.boot.app.dto.AppUserVO;
import com.mugsun.boot.auth.AuthLoginService;
import com.mugsun.boot.client.ClientService;
import com.mugsun.boot.client.entity.SysClient;
import com.mugsun.boot.common.constant.ClientConstants;
import com.mugsun.boot.common.crypto.GmCryptoConfig;
import com.mugsun.boot.common.crypto.Sm2Util;
import com.mugsun.core.tool.api.R;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;
import java.util.Map;

/**
 * App 认证：滑块 ticket + SM2 密码；不接受 PC 图形验证码。
 */
@RestController
@RequestMapping("/app/auth")
public class AppAuthController {

	private final AppSliderCaptchaService sliderCaptchaService;
	private final AuthLoginService authLoginService;
	private final ClientService clientService;
	private final GmCryptoConfig gmCryptoConfig;
	private final AppHomeService homeService;

	public AppAuthController(AppSliderCaptchaService sliderCaptchaService, AuthLoginService authLoginService,
							 ClientService clientService, GmCryptoConfig gmCryptoConfig,
							 AppHomeService homeService) {
		this.sliderCaptchaService = sliderCaptchaService;
		this.authLoginService = authLoginService;
		this.clientService = clientService;
		this.gmCryptoConfig = gmCryptoConfig;
		this.homeService = homeService;
	}

	@GetMapping("/sm2-public-key")
	public R<Map<String, Object>> sm2PublicKey() {
		Map<String, Object> data = new HashMap<>();
		data.put("gmEnabled", gmCryptoConfig.isGmEnabled());
		data.put("publicKey", gmCryptoConfig.isGmEnabled() ? Sm2Util.publicKey() : null);
		return R.data(data);
	}

	@PostMapping("/login")
	public R<Map<String, Object>> login(@RequestBody AppLoginDTO dto, HttpServletRequest request) {
		sliderCaptchaService.consumeTicket(dto == null ? null : dto.ticket());
		SysClient client = clientService.loadClient(ClientConstants.APP_CLIENT_ID);
		return R.data(authLoginService.loginByPassword(
			dto == null ? null : dto.username(),
			dto == null ? null : dto.password(),
			dto == null ? null : dto.tenantId(),
			client, request));
	}

	@PostMapping("/two-factor")
	public R<Map<String, Object>> twoFactor(@RequestBody Map<String, String> body, HttpServletRequest request) {
		SysClient client = clientService.loadClient(ClientConstants.APP_CLIENT_ID);
		return R.data(authLoginService.completeTwoFactor(
			body.get("twoFactorToken"), body.get("code"), client, request));
	}

	@PostMapping("/logout")
	@SaCheckLogin
	public R<Void> logout() {
		authLoginService.logout();
		return R.success("已登出");
	}

	@GetMapping("/me")
	@SaCheckLogin
	public R<AppUserVO> me() {
		return R.data(homeService.me());
	}
}
