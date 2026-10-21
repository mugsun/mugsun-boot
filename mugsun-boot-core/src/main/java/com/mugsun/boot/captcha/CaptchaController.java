package com.mugsun.boot.captcha;

import com.mugsun.boot.captcha.dto.CaptchaConfigVO;
import com.mugsun.boot.captcha.dto.ChallengeRequest;
import com.mugsun.boot.captcha.dto.ChallengeVO;
import com.mugsun.boot.captcha.dto.TokenVO;
import com.mugsun.boot.captcha.dto.VerifyRequest;
import com.mugsun.boot.client.entity.SysClient;
import com.mugsun.boot.common.web.ClientIpResolver;
import com.mugsun.core.tool.api.R;
import com.mugsun.core.tool.exception.ServiceException;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 行为验证码（匿名）：开关提示、取挑战、校验 */
@RestController
@RequestMapping("/auth/captcha")
public class CaptchaController {

	private final CaptchaGate gate;
	private final SliderCaptchaService slider;
	private final CaptchaProperties props;
	private final com.mugsun.boot.auth.register.RegisterGate registerGate;

	public CaptchaController(CaptchaGate gate, SliderCaptchaService slider, CaptchaProperties props,
							 com.mugsun.boot.auth.register.RegisterGate registerGate) {
		this.gate = gate;
		this.slider = slider;
		this.props = props;
		this.registerGate = registerGate;
	}

	@GetMapping("/config")
	public R<CaptchaConfigVO> config(@RequestParam(defaultValue = CaptchaGate.SCENE_LOGIN) String scene,
									 @RequestParam(required = false) String clientId,
									 @RequestParam(required = false) String tenantId) {
		String sc = requireScene(scene);
		SysClient client = gate.resolveClient(clientId);
		boolean on = gate.required(sc, client, CaptchaGate.effectiveTenant(sc, tenantId));
		java.util.Set<CaptchaType> types = gate.enabledTypes(sc, CaptchaGate.effectiveTenant(sc, tenantId));
		String type = types.size() > 1 ? "RANDOM" : types.iterator().next().name();
		return R.data(new CaptchaConfigVO(on, type, sc, registerGate.mode().name()));
	}

	@PostMapping("/challenge")
	public R<ChallengeVO> challenge(@RequestBody(required = false) ChallengeRequest req, HttpServletRequest request) {
		String sc = requireScene(req == null ? null : req.scene());
		SysClient client = gate.resolveClient(req == null ? null : req.clientId());
		String tenant = CaptchaGate.effectiveTenant(sc, req == null ? null : req.tenantId());
		if (!gate.required(sc, client, tenant)) {
			throw CaptchaException.disabled();
		}
		return R.data(slider.challenge(sc, client.getClientId(), tenant, req == null ? null : req.inputMode(),
			ClientIpResolver.clientIp(request), gate.enabledTypes(sc, tenant), gate.statTenant(sc, tenant),
			request.getHeader("Accept-Language") == null ? null : request.getLocale()));
	}

	@PostMapping("/verify")
	public R<TokenVO> verify(@RequestBody(required = false) VerifyRequest req, HttpServletRequest request) {
		return R.data(slider.verify(req, ClientIpResolver.clientIp(request), request.getHeader("User-Agent")));
	}

	private static String requireScene(String scene) {
		String sc = scene == null || scene.isBlank() ? CaptchaGate.SCENE_LOGIN : scene.trim();
		if (!CaptchaGate.WEB_SCENES.contains(sc)) {
			throw new ServiceException("验证场景无效，请刷新页面后重试");
		}
		return sc;
	}
}
