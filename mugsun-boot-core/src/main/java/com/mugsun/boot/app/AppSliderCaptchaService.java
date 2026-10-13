package com.mugsun.boot.app;

import com.mugsun.boot.app.dto.AppSliderCheckDTO;
import com.mugsun.boot.app.dto.AppSliderGenerateVO;
import com.mugsun.boot.app.dto.AppSliderTrackPoint;
import com.mugsun.boot.captcha.CaptchaCodes;
import com.mugsun.boot.captcha.CaptchaException;
import com.mugsun.boot.captcha.SliderCaptchaService;
import com.mugsun.core.tool.exception.ServiceException;
import com.mugsun.boot.captcha.dto.ChallengeVO;
import com.mugsun.boot.captcha.dto.TrackPoint;
import com.mugsun.boot.captcha.dto.VerifyRequest;
import com.mugsun.boot.common.constant.ClientConstants;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * App 拼图滑块（/app/captcha，接口形状保持兼容）：内部委托统一的 {@link SliderCaptchaService}。
 * moveX 视为底图原始像素；ticket 即一次性 captchaToken，绑定 app-login 场景。
 */
@Service
public class AppSliderCaptchaService {

	static final String SCENE = "app-login";
	/** App 登录前不知道租户，ticket 绑定固定占位 */
	static final String ANY_TENANT = "-";

	private final SliderCaptchaService engine;

	public AppSliderCaptchaService(SliderCaptchaService engine) {
		this.engine = engine;
	}

	public AppSliderGenerateVO generate(String clientIp) {
		ChallengeVO c = engine.challenge(SCENE, ClientConstants.APP_CLIENT_ID, ANY_TENANT, clientIp);
		return new AppSliderGenerateVO(c.captchaId(), c.background(), c.piece(), c.bgWidth(), c.bgHeight(), c.pieceWidth());
	}

	public String check(AppSliderCheckDTO dto, String clientIp) {
		if (dto == null) {
			throw new ServiceException("请完成滑动验证");
		}
		List<TrackPoint> track = dto.tracks() == null ? null : dto.tracks().stream()
			.map(p -> p == null ? null : toPoint(p)).toList();
		VerifyRequest req = new VerifyRequest(dto.captchaId(), SliderCaptchaService.MODE_POINTER,
			engineWidth(), dto.startTime(), dto.endTime(), track, new VerifyRequest.Answer((double) dto.moveX()), null);
		try {
			return engine.verify(req, clientIp, null).captchaToken();
		} catch (CaptchaException e) {
			throw legacy(e, "请将滑块对齐缺口");
		}
	}

	/** 登录核销：一次性、原子删除 */
	public void consumeTicket(String ticket) {
		try {
			engine.consume(ticket, SCENE, ClientConstants.APP_CLIENT_ID, ANY_TENANT, null, null);
		} catch (CaptchaException e) {
			throw new ServiceException("请完成滑动验证");
		}
	}

	/** 集成测试读取缺口横坐标；未挂 HTTP。 */
	public int peekExpectedX(String captchaId) {
		return engine.peekAnswerX(captchaId);
	}

	/** App 端沿用旧约定（R.code=400 + 中文提示）；限流仍返回 40012 + retryAfter */
	private static ServiceException legacy(CaptchaException e, String failMsg) {
		if (e.getCode() == CaptchaCodes.RATE_LIMITED) {
			return e;
		}
		return new ServiceException(e.getCode() == CaptchaCodes.EXPIRED ? "验证已过期，请重试" : failMsg);
	}

	private static TrackPoint toPoint(AppSliderTrackPoint p) {
		return new TrackPoint((double) p.x(), (double) p.y(), p.t());
	}

	private int engineWidth() {
		return engine.bgWidth();
	}
}
