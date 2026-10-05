package com.mugsun.boot.track.controller;

import cn.dev33.satoken.annotation.SaCheckLogin;
import cn.dev33.satoken.annotation.SaCheckPermission;
import cn.dev33.satoken.stp.StpUtil;
import com.mugsun.boot.common.constant.TrackConstants;
import com.mugsun.boot.track.TrackVisualService;
import com.mugsun.boot.track.entity.TrackVisualRule;
import com.mugsun.core.tool.api.R;
import com.mugsun.core.tool.exception.ServiceException;
import com.mybatisflex.core.paginate.Page;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 圈选式可视化埋点管理 API（G104）：圈选令牌签发 / 草稿查询·确认·丢弃 / 规则 CRUD。
 * <p>写方法全部显式 @SaCheckPermission（/system/** 写操作 PermissionGuardInterceptor fail-closed 兜底）。
 * <p><b>分层纪律</b>：权限校验（StpUtil，业务库）必须留在本层；业务与 DB 读写全部委托
 * {@link TrackVisualService}（@TrackDS 路由埋点库）——在 @TrackDS 切面范围内调用权限校验会把业务库查询误路由到埋点库。
 */
@RestController
@RequestMapping("/system/track/visual")
@SaCheckLogin
public class TrackVisualController {

	private final TrackVisualService visualService;

	public TrackVisualController(TrackVisualService visualService) {
		this.visualService = visualService;
	}

	/** 签发圈选令牌：{appKey 必填, targetUrl 可空} → {token, url, expireSeconds}（url 已拼 __mst_inspect；targetUrl 空则 url=null） */
	@PostMapping("/token")
	@SaCheckPermission(TrackConstants.PERM_VISUAL_EDIT)
	public R<Map<String, Object>> token(@RequestBody Map<String, Object> body) {
		String appKey = body.get("appKey") == null ? null : body.get("appKey").toString();
		String targetUrl = body.get("targetUrl") == null ? null : body.get("targetUrl").toString();
		return R.data(visualService.createToken(appKey, targetUrl, StpUtil.getLoginIdAsLong()));
	}

	/** 草稿列表（令牌有效期内；管理端 3s 轮询用）：令牌归属租户校验后返回草稿 JSON 列表 */
	@GetMapping("/drafts")
	@SaCheckPermission(TrackConstants.PERM_VISUAL_EDIT)
	public R<List<Map<String, Object>>> drafts(@RequestParam String token) {
		return R.data(visualService.drafts(token));
	}

	/** 草稿确认成规则：{token, draftId, eventName 可空（缺省取草稿值）} → 规则行（重复圈选 = 更新而非堆行） */
	@PostMapping("/drafts/confirm")
	@SaCheckPermission(TrackConstants.PERM_VISUAL_EDIT)
	public R<TrackVisualRule> confirm(@RequestBody(required = false) Map<String, Object> body) {
		if (body == null) {
			throw new ServiceException("请先选择要确认的草稿");
		}
		String token = body.get("token") == null ? null : body.get("token").toString();
		String draftId = body.get("draftId") == null ? null : body.get("draftId").toString();
		String eventName = body.get("eventName") == null ? null : body.get("eventName").toString();
		if ("null".equals(token)) {
			token = null;
		}
		if ("null".equals(draftId)) {
			draftId = null;
		}
		TrackVisualRule rule = visualService.confirmDraft(token, draftId, eventName, StpUtil.getLoginIdAsLong());
		String name = rule.getEventName();
		if (name == null || name.isBlank()) {
			name = "这条规则";
		}
		R<TrackVisualRule> resp = R.data(rule);
		resp.setMsg("已确认为圈选规则「" + name + "」。配置下发会带上这条规则");
		return resp;
	}

	/** 草稿丢弃：{token, draftId} */
	@PostMapping("/drafts/discard")
	@SaCheckPermission(TrackConstants.PERM_VISUAL_EDIT)
	public R<Void> discard(@RequestBody(required = false) Map<String, Object> body) {
		if (body == null) {
			throw new ServiceException("请先选择要丢弃的草稿");
		}
		String token = body.get("token") == null ? null : body.get("token").toString();
		String draftId = body.get("draftId") == null ? null : body.get("draftId").toString();
		if ("null".equals(token)) {
			token = null;
		}
		if ("null".equals(draftId)) {
			draftId = null;
		}
		return R.success(visualService.discardDraft(token, draftId));
	}

	/** 规则分页：appKey 必填 + status 可选（本租户行级隔离由 Flex 插件自动拼条件），update_time 倒序 */
	@GetMapping("/rule/page")
	@SaCheckPermission(TrackConstants.PERM_VISUAL_LIST)
	public R<Page<TrackVisualRule>> rulePage(@RequestParam String appKey,
											 @RequestParam(required = false) Integer status,
											 @RequestParam(defaultValue = "1") long pageNum,
											 @RequestParam(defaultValue = "10") long pageSize) {
		return R.data(visualService.rulePage(appKey, status, pageNum, pageSize));
	}

	/** 规则编辑：仅 eventName/routePath/matchText/status 可改（selector 只读，改 selector = 重新圈选） */
	@PostMapping("/rule/submit")
	@SaCheckPermission(TrackConstants.PERM_VISUAL_EDIT)
	public R<Void> ruleSubmit(@RequestBody(required = false) Map<String, Object> body) {
		if (body == null || body.get("id") == null || String.valueOf(body.get("id")).isBlank()
			|| "null".equals(String.valueOf(body.get("id")))) {
			throw new ServiceException("请先在列表里打开要编辑的规则");
		}
		String text = String.valueOf(body.get("id")).trim();
		if (!text.matches("\\d{1,19}")) {
			throw new ServiceException("规则编号请填写数字，可以从列表里复制");
		}
		return R.success(visualService.ruleSubmit(Long.valueOf(text), body, StpUtil.getLoginIdAsLong()));
	}

	/** 规则删除（逻辑删除；缓存即时失效后 config 不再下发） */
	@PostMapping("/rule/remove")
	@SaCheckPermission(TrackConstants.PERM_VISUAL_EDIT)
	public R<Void> ruleRemove(@RequestBody(required = false) Map<String, Object> body) {
		if (body == null || body.get("id") == null || String.valueOf(body.get("id")).isBlank()
			|| "null".equals(String.valueOf(body.get("id")))) {
			throw new ServiceException("请先在列表里选择要删除的规则");
		}
		String text = String.valueOf(body.get("id")).trim();
		if (!text.matches("\\d{1,19}")) {
			throw new ServiceException("规则编号请填写数字，可以从列表里复制");
		}
		String name = visualService.ruleRemove(Long.valueOf(text)).getEventName();
		if (name == null || name.isBlank()) {
			name = "这条规则";
		}
		return R.success("已删除「" + name + "」。配置下发里不会再带上这条规则");
	}
}
