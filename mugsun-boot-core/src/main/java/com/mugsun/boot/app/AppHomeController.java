package com.mugsun.boot.app;

import cn.dev33.satoken.annotation.SaCheckLogin;
import com.mugsun.boot.app.dto.AppHomeVO;
import com.mugsun.core.tool.api.R;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/app")
@SaCheckLogin
public class AppHomeController {

	private final AppHomeService homeService;

	public AppHomeController(AppHomeService homeService) {
		this.homeService = homeService;
	}

	@GetMapping("/home")
	public R<AppHomeVO> home() {
		return R.data(homeService.home());
	}
}
