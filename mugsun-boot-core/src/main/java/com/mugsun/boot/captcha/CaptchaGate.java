package com.mugsun.boot.captcha;

import com.mugsun.boot.client.ClientService;
import com.mugsun.boot.client.entity.SysClient;
import com.mugsun.boot.common.constant.TenantConstants;
import com.mugsun.boot.common.web.ClientIpResolver;
import com.mugsun.boot.system.entity.SysTenant;
import com.mugsun.boot.tenant.TenantValidator;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Set;

/**
 * 业务接口验证码闸门：按服务端解析的真实租户/客户端重新判定（fail-closed），
 * 需要时核销 captchaToken。前端的 /auth/captcha/config 只是提示。
 */
@Component
public class CaptchaGate {

	private static final Logger log = LoggerFactory.getLogger(CaptchaGate.class);

	public static final String SCENE_LOGIN = "login";
	public static final String SCENE_REGISTER = "register";
	/** 发送短信验证码（防短信轰炸） */
	public static final String SCENE_SMS = "sms";
	public static final String SCENE_FORGET = "forget";
	public static final Set<String> WEB_SCENES = Set.of(SCENE_LOGIN, SCENE_REGISTER, SCENE_SMS, SCENE_FORGET);

	private final CaptchaProperties props;
	private final SliderCaptchaService slider;
	private final ClientService clientService;
	private final TenantValidator tenantValidator;

	public CaptchaGate(CaptchaProperties props, SliderCaptchaService slider, ClientService clientService,
					   TenantValidator tenantValidator) {
		this.props = props;
		this.slider = slider;
		this.clientService = clientService;
		this.tenantValidator = tenantValidator;
	}

	/** 当前是否滑块模式（类型只看服务端配置） */
	public boolean isSlider() {
		return props.isSliderType();
	}

	public static String normalizeTenant(String tenantId) {
		return tenantId == null || tenantId.isBlank() ? TenantConstants.DEFAULT_TENANT_ID : tenantId.trim();
	}

	public SysClient resolveClient(String clientId) {
		return clientService.loadClient(clientId == null ? null : clientId.trim());
	}

	/** 该场景对该租户/客户端是否需要验证码；查询异常时按需要处理 */
	public boolean required(String scene, SysClient client, String tenantId) {
		if (!props.isEnabled()) {
			return false;
		}
		if (SCENE_LOGIN.equals(scene) && client != null && !Integer.valueOf(1).equals(client.getCaptchaEnabled())) {
			return false;
		}
		return tenantEnabled(tenantId);
	}

	boolean tenantEnabled(String tenantId) {
		try {
			// 复用租户校验器的 60s 本地缓存；开关修改时会 evict 本节点缓存
			SysTenant t = tenantValidator.loadTenant(tenantId);
			return t == null || !Integer.valueOf(0).equals(t.getCaptchaEnabled());
		} catch (RuntimeException e) {
			log.warn("读取租户验证码开关失败，按开启处理：{}", e.getMessage());
			return true;
		}
	}

	/** 滑块模式下的业务前置校验；不需要验证码时直接放行 */
	public void check(String scene, SysClient client, String tenantId, String captchaToken, HttpServletRequest request) {
		if (!required(scene, client, tenantId)) {
			return;
		}
		slider.consume(captchaToken, scene, client.getClientId(), tenantId,
			ClientIpResolver.clientIp(request), request == null ? null : request.getHeader("User-Agent"));
	}
}
