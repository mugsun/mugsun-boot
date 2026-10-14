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

	/** 与租户无关的场景（发送短信）绑定的固定租户占位 */
	public static final String NO_TENANT = "-";

	public static String normalizeTenant(String tenantId) {
		return tenantId == null || tenantId.isBlank() ? TenantConstants.DEFAULT_TENANT_ID : tenantId.trim();
	}

	/** 场景实际绑定的租户：发送短信与租户无关，一律用占位，不采信客户端 tenantId */
	public static String effectiveTenant(String scene, String tenantId) {
		return SCENE_SMS.equals(scene) ? NO_TENANT : normalizeTenant(tenantId);
	}

	public SysClient resolveClient(String clientId) {
		return clientService.loadClient(clientId == null ? null : clientId.trim());
	}

	/**
	 * 该场景对该租户/客户端是否需要验证码；查询异常时按需要处理（fail-closed）。
	 * <ul>
	 *   <li>发送短信：只看全局开关（短信与租户无关，客户端填的 tenantId 不可信）</li>
	 *   <li>滑块模式：clientId 由请求方自选、无法可信解析，客户端开关不再能关闭验证码，只有租户开关（平台超管）可以</li>
	 *   <li>旧数字码模式：登录仍沿用客户端开关</li>
	 * </ul>
	 */
	public boolean required(String scene, SysClient client, String tenantId) {
		if (!props.isEnabled()) {
			return false;
		}
		if (SCENE_SMS.equals(scene)) {
			return true;
		}
		if (!isSlider() && SCENE_LOGIN.equals(scene) && client != null
			&& !Integer.valueOf(1).equals(client.getCaptchaEnabled())) {
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

	/**
	 * 该场景/租户启用的题型（服务端随机选题用）。发短信与租户无关，用平台默认；
	 * 租户未配置、配置非法或读取失败 → 平台默认 default-types（仍非法则仅 SLIDER）。
	 */
	public Set<CaptchaType> enabledTypes(String scene, String tenantId) {
		Set<CaptchaType> def = CaptchaType.parseAll(props.getDefaultTypes());
		if (def == null) {
			def = java.util.EnumSet.of(CaptchaType.SLIDER);
		}
		if (SCENE_SMS.equals(scene)) {
			return def;
		}
		try {
			SysTenant t = tenantValidator.loadTenant(tenantId);
			Set<CaptchaType> own = t == null ? null : CaptchaType.parseCsv(t.getCaptchaTypes());
			return own == null ? def : own;
		} catch (RuntimeException e) {
			log.warn("读取租户验证码类型失败，按平台默认：{}", e.getMessage());
			return def;
		}
	}

	/**
	 * Redis 计数/去重键用的规范化租户码：租户存在 → 库里的 tenant_code；不存在、短信场景或读取失败 → "_unknown"。
	 * 不把请求里未经解析的 tenantId 拼进键名。
	 */
	public String statTenant(String scene, String tenantId) {
		if (SCENE_SMS.equals(scene)) {
			return "_unknown";
		}
		try {
			SysTenant t = tenantValidator.loadTenant(tenantId);
			return t == null || t.getTenantCode() == null ? "_unknown" : t.getTenantCode();
		} catch (RuntimeException e) {
			return "_unknown";
		}
	}

	/** 发短信⓪：短窗口频率限制（所有请求计数，自动恢复） */
	public void hitSmsBurstIp(HttpServletRequest request) {
		slider.hitSmsBurstIp(ClientIpResolver.clientIp(request));
	}

	/** 发短信②之后：手机号短窗口（仅 token 核销成功后计数） */
	public void hitSmsBurstPhone(String phone) {
		slider.hitSmsBurstPhone(phone);
	}

	/** 发短信①：只读检查配额（不计数） */
	public void checkSmsQuota(String phone, HttpServletRequest request) {
		slider.checkSmsQuota(phone, ClientIpResolver.clientIp(request));
	}

	/** 发短信③：token 核销后原子计数，超限 40012 */
	public void takeSmsQuota(String phone, HttpServletRequest request) {
		slider.takeSmsQuota(phone, ClientIpResolver.clientIp(request));
	}

	/** 发短信失败审计（计数不回退） */
	public void smsSendFailed(String phone, HttpServletRequest request, Exception e) {
		slider.smsSendFailed(phone, ClientIpResolver.clientIp(request), e.getClass().getSimpleName() + ": " + e.getMessage());
	}

	/** 滑块模式下的业务前置校验；不需要验证码时直接放行 */
	public void check(String scene, SysClient client, String tenantId, String captchaToken, HttpServletRequest request) {
		if (!required(scene, client, tenantId)) {
			return;
		}
		slider.consume(captchaToken, scene, client.getClientId(), effectiveTenant(scene, tenantId),
			ClientIpResolver.clientIp(request), request == null ? null : request.getHeader("User-Agent"));
	}
}
