package com.mugsun.boot.system.controller;

import com.mugsun.boot.captcha.CaptchaType;
import org.junit.jupiter.api.Test;

import java.util.EnumSet;

import static org.assertj.core.api.Assertions.assertThat;

/** 保存租户题型时的 data.warning（不需要容器） */
class SysTenantCaptchaWarningTest {

	@Test
	void rotateOnlyWarns() {
		assertThat(SysTenantController.captchaTypesWarning(EnumSet.of(CaptchaType.ROTATE)))
			.isEqualTo(SysTenantController.WARN_ROTATE_ONLY).contains("ROTATE", "唯一防线");
	}

	@Test
	void keyboardWarningUnchanged() {
		assertThat(SysTenantController.captchaTypesWarning(EnumSet.of(CaptchaType.ICON_CLICK)))
			.isEqualTo(SysTenantController.WARN_NO_KEYBOARD).contains("40015");
	}

	@Test
	void noWarningWhenRotateHasCompany() {
		assertThat(SysTenantController.captchaTypesWarning(EnumSet.of(CaptchaType.ROTATE, CaptchaType.SLIDER))).isNull();
		assertThat(SysTenantController.captchaTypesWarning(EnumSet.of(CaptchaType.ROTATE, CaptchaType.WORD_CLICK))).isNull();
		assertThat(SysTenantController.captchaTypesWarning(EnumSet.of(CaptchaType.SLIDER))).isNull();
	}
}
