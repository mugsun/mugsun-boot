package com.mugsun.boot.captcha;

import com.mugsun.boot.captcha.image.BackgroundLibrary;
import com.mugsun.boot.captcha.image.BuiltinAssets;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 启动自检：底图 / 旋转图 / 图标是否仍是仓库自带素材（公开，可被做差或预建答案表）。
 * prod 下命中即 WARN；结果通过 {@link #builtinAssetsInUse()} / {@link #details()} 暴露，供后续 doctor 标红。
 */
@Component
public class CaptchaAssetCheck {

	private static final Logger log = LoggerFactory.getLogger(CaptchaAssetCheck.class);

	private final CaptchaProperties props;
	private final BackgroundLibrary library;
	private final Environment env;
	private volatile Map<String, Boolean> details;

	public CaptchaAssetCheck(CaptchaProperties props, BackgroundLibrary library, Environment env) {
		this.props = props;
		this.library = library;
		this.env = env;
	}

	@EventListener(ApplicationReadyEvent.class)
	public void onReady() {
		Map<String, Boolean> d = check();
		boolean prod = env != null && env.acceptsProfiles(Profiles.of("prod"));
		if (prod && props.isEnabled() && d.containsValue(true)) {
			log.warn("验证码仍在使用仓库自带（公开）素材：{}。攻击者可用公开原图做差定位缺口、预建旋转/图标答案表；"
				+ "生产请把 mugsun.captcha.background.dir / rotate.dir / icon-dir 换成私有图库并定期更换", d);
		}
	}

	/** 重新计算并缓存：bg / rotate / icons 各自是否为自带素材 */
	public Map<String, Boolean> check() {
		Map<String, Boolean> d = new LinkedHashMap<>();
		d.put("background", library.usesBuiltin());
		d.put("rotate", BuiltinAssets.isBuiltin(props.getRotate().getDir(), BuiltinAssets.ROTATE));
		d.put("icons", BuiltinAssets.isBuiltin(props.getIconDir(), BuiltinAssets.ICONS));
		details = Map.copyOf(d);
		return d;
	}

	/** 任一类素材仍是仓库自带 → true（doctor 标红用） */
	public boolean builtinAssetsInUse() {
		return details().containsValue(true);
	}

	public Map<String, Boolean> details() {
		Map<String, Boolean> d = details;
		return d != null ? d : check();
	}
}
