package com.mugsun.boot.captcha;

import com.mugsun.boot.common.setting.InvalidSettingException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** #77：seed-warp-strength 启动校验与渲染钳位 [0, 0.35] 对齐 */
class CaptchaSeedWarpStrengthTest {

	@Test
	@DisplayName("默认 0.14，validate 不改")
	void defaultUnchanged() {
		CaptchaProperties p = new CaptchaProperties();
		p.validate();
		assertThat(p.getClick().getSeedWarpStrength()).isEqualTo(0.14);
	}

	@Test
	@DisplayName("0=关；边界 0.35 保留")
	void zeroAndMaxKept() {
		CaptchaProperties p = new CaptchaProperties();
		p.getClick().setSeedWarpStrength(0);
		p.validate();
		assertThat(p.getClick().getSeedWarpStrength()).isEqualTo(0);

		p.getClick().setSeedWarpStrength(CaptchaProperties.Click.SEED_WARP_STRENGTH_MAX);
		p.validate();
		assertThat(p.getClick().getSeedWarpStrength())
			.isEqualTo(CaptchaProperties.Click.SEED_WARP_STRENGTH_MAX);
	}

	@Test
	@DisplayName("越界钳到 [0, 0.35]")
	void outOfRangeClamped() {
		CaptchaProperties hi = new CaptchaProperties();
		hi.getClick().setSeedWarpStrength(0.9);
		hi.validate();
		assertThat(hi.getClick().getSeedWarpStrength())
			.isEqualTo(CaptchaProperties.Click.SEED_WARP_STRENGTH_MAX);

		CaptchaProperties lo = new CaptchaProperties();
		lo.getClick().setSeedWarpStrength(-0.2);
		lo.validate();
		assertThat(lo.getClick().getSeedWarpStrength()).isEqualTo(0);
	}

	@Test
	@DisplayName("NaN / 无穷启动失败")
	void nanOrInfFails() {
		CaptchaProperties nan = new CaptchaProperties();
		nan.getClick().setSeedWarpStrength(Double.NaN);
		assertThatThrownBy(nan::validate)
			.isInstanceOf(InvalidSettingException.class)
			.hasMessageContaining(CaptchaProperties.PROP_SEED_WARP_STRENGTH);

		CaptchaProperties inf = new CaptchaProperties();
		inf.getClick().setSeedWarpStrength(Double.POSITIVE_INFINITY);
		assertThatThrownBy(inf::validate)
			.isInstanceOf(InvalidSettingException.class)
			.hasMessageContaining(CaptchaProperties.PROP_SEED_WARP_STRENGTH);
	}
}
