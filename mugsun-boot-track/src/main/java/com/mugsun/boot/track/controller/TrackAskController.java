package com.mugsun.boot.track.controller;

import cn.dev33.satoken.annotation.SaCheckLogin;
import cn.dev33.satoken.annotation.SaCheckPermission;
import com.mugsun.boot.common.constant.TrackConstants;
import com.mugsun.boot.track.TrackAskService;
import com.mugsun.core.tool.api.R;
import com.mugsun.core.tool.exception.ServiceException;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 埋点问数据 API：{@code POST /system/track/ask}。
 * <p>入口权限 {@link TrackConstants#PERM_ASK_LIST}；意图落地再校验对应分析读权。
 * 数字只编排既有 TrackAnalysis/Funnel/Retention；相关回放仅元数据（最多 5，有错优先）。
 */
@RestController
@RequestMapping("/system/track")
@SaCheckLogin
public class TrackAskController {

	private final TrackAskService askService;

	public TrackAskController(TrackAskService askService) {
		this.askService = askService;
	}

	@PostMapping("/ask")
	@SaCheckPermission(TrackConstants.PERM_ASK_LIST)
	public R<Map<String, Object>> ask(@RequestBody Map<String, Object> body) {
		if (body == null) {
			throw new ServiceException("请提交 appKey 与 question");
		}
		String appKey = text(body.get("appKey"));
		String question = text(body.get("question"));
		Integer days = asInt(body.get("days"));
		return R.data(askService.ask(appKey, question, days));
	}

	private static String text(Object raw) {
		if (raw == null) {
			return null;
		}
		String s = String.valueOf(raw).trim();
		return s.isEmpty() || "null".equalsIgnoreCase(s) ? null : s;
	}

	private static Integer asInt(Object raw) {
		if (raw == null) {
			return null;
		}
		if (raw instanceof Number n) {
			return n.intValue();
		}
		try {
			return Integer.parseInt(String.valueOf(raw).trim());
		} catch (NumberFormatException e) {
			throw new ServiceException("days 须为 1 到 " + TrackConstants.ANALYSIS_DAYS_MAX + " 的整数");
		}
	}
}
