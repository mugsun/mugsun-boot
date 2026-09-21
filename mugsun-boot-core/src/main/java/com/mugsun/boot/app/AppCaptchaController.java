package com.mugsun.boot.app;

import com.mugsun.boot.app.dto.AppSliderCheckDTO;
import com.mugsun.boot.app.dto.AppSliderGenerateVO;
import com.mugsun.boot.app.dto.AppTicketVO;
import com.mugsun.core.tool.api.R;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * App 拼图滑块：出题不含答案，校验通过签发一次性 ticket。
 */
@RestController
@RequestMapping("/app/captcha")
public class AppCaptchaController {

	private final AppSliderCaptchaService sliderCaptchaService;

	public AppCaptchaController(AppSliderCaptchaService sliderCaptchaService) {
		this.sliderCaptchaService = sliderCaptchaService;
	}

	@GetMapping("/generate")
	public R<AppSliderGenerateVO> generate(HttpServletRequest request) {
		return R.data(sliderCaptchaService.generate(request.getRemoteAddr()));
	}

	@PostMapping("/check")
	public R<AppTicketVO> check(@RequestBody AppSliderCheckDTO dto, HttpServletRequest request) {
		return R.data(new AppTicketVO(sliderCaptchaService.check(dto, request.getRemoteAddr())));
	}
}
