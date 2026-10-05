package com.mugsun.boot.ai.controller;

import cn.dev33.satoken.annotation.SaCheckLogin;
import cn.dev33.satoken.annotation.SaCheckPermission;
import com.mugsun.boot.ai.AiConstants;
import com.mugsun.boot.ai.entity.AiChannelBind;
import com.mugsun.boot.ai.service.AiChannelBizService;
import com.mugsun.boot.common.repeat.RepeatSubmit;
import com.mugsun.core.tool.api.R;
import com.mugsun.core.tool.exception.ServiceException;
import com.mybatisflex.core.paginate.Page;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/system/ai/channel")
@SaCheckLogin
public class AiChannelController {

	private final AiChannelBizService service;

	public AiChannelController(AiChannelBizService service) {
		this.service = service;
	}

	@GetMapping("/page")
	@SaCheckPermission(AiConstants.PERM_CHANNEL_LIST)
	public R<Page<AiChannelBind>> page(@RequestParam(defaultValue = "1") long pageNum,
									   @RequestParam(defaultValue = "20") long pageSize,
									   @RequestParam(required = false) String alias) {
		return R.data(service.page(pageNum, pageSize, alias));
	}

	@RepeatSubmit
	@PostMapping("/submit")
	@SaCheckPermission(AiConstants.PERM_CHANNEL_SAVE)
	public R<AiChannelBind> submit(@RequestBody(required = false) AiChannelBind body) {
		boolean create = body == null || body.getId() == null;
		AiChannelBind saved = service.submit(body);
		R<AiChannelBind> result = R.data(saved);
		result.setMsg(create
			? "已添加。可以点调试，确认消息能发出去"
			: "已保存。列表会显示最新内容");
		return result;
	}

	@RepeatSubmit
	@PostMapping("/remove")
	@SaCheckPermission(AiConstants.PERM_CHANNEL_REMOVE)
	public R<Void> remove(@RequestParam(required = false) String ids,
						 @RequestBody(required = false) Map<String, Object> body) {
		List<AiChannelBind> removed = service.remove(parseIds(ids, body));
		if (removed.size() == 1) {
			String name = removed.get(0).getAlias();
			return R.success("已删除「" + (name == null || name.isBlank() ? "这个渠道" : name) + "」。列表里不会再出现");
		}
		return R.success("已删除 " + removed.size() + " 个渠道。列表里不会再出现");
	}

	@PostMapping("/debug")
	@SaCheckPermission(AiConstants.PERM_CHANNEL_DEBUG)
	public R<Map<String, Object>> debug(@RequestParam(required = false) String id,
										@RequestBody(required = false) Map<String, Object> body) {
		Object raw = id;
		if ((raw == null || String.valueOf(raw).isBlank()) && body != null) {
			raw = body.get("id");
		}
		if (raw == null || String.valueOf(raw).isBlank()) {
			throw new ServiceException("请先在列表里选择要调试的渠道");
		}
		Map<String, Object> data = service.debug(parseId(raw));
		R<Map<String, Object>> result = R.data(data);
		result.setMsg(String.valueOf(data.get("message")));
		if (!Boolean.TRUE.equals(data.get("ok"))) {
			result.setSuccess(false);
			result.setCode(400);
		}
		return result;
	}

	private static List<Long> parseIds(String ids, Map<String, Object> body) {
		String raw = ids;
		if ((raw == null || raw.isBlank()) && body != null) {
			Object value = body.get("ids") != null ? body.get("ids") : body.get("id");
			raw = value == null ? null : String.valueOf(value);
		}
		if (raw == null || raw.isBlank() || "null".equals(raw)) {
			throw new ServiceException("请先选择要删除的渠道");
		}
		List<Long> idList = new ArrayList<>();
		for (String part : raw.split(",")) {
			if (part == null || part.isBlank()) {
				continue;
			}
			idList.add(parseId(part));
		}
		if (idList.isEmpty()) {
			throw new ServiceException("请先选择要删除的渠道");
		}
		return idList;
	}

	private static Long parseId(Object raw) {
		String text = String.valueOf(raw).trim();
		if (!text.matches("\\d{1,19}")) {
			throw new ServiceException("渠道编号请填写数字，可以从列表里复制");
		}
		return Long.valueOf(text);
	}
}
