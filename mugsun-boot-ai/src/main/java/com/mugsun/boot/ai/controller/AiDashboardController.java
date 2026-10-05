package com.mugsun.boot.ai.controller;

import cn.dev33.satoken.annotation.SaCheckLogin;
import cn.dev33.satoken.annotation.SaCheckPermission;
import com.mugsun.boot.ai.AiConstants;
import com.mugsun.boot.ai.entity.AiDashboard;
import com.mugsun.boot.ai.service.AiDashboardBizService;
import com.mugsun.boot.common.repeat.RepeatSubmit;
import com.mugsun.core.tool.api.R;
import com.mugsun.core.tool.exception.ServiceException;
import com.mybatisflex.core.paginate.Page;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/system/ai/dashboard")
@SaCheckLogin
public class AiDashboardController {

	private final AiDashboardBizService service;

	public AiDashboardController(AiDashboardBizService service) {
		this.service = service;
	}

	@GetMapping("/page")
	@SaCheckPermission(AiConstants.PERM_DASHBOARD_DESIGN)
	public R<Page<AiDashboard>> page(@RequestParam(defaultValue = "1") long pageNum,
									 @RequestParam(defaultValue = "20") long pageSize,
									 @RequestParam(required = false) String name) {
		return R.data(service.page(pageNum, pageSize, name));
	}

	@GetMapping("/detail")
	@SaCheckPermission(AiConstants.PERM_DASHBOARD_DESIGN)
	public R<AiDashboard> detail(@RequestParam(required = false) String id) {
		if (id == null || id.isBlank()) {
			return R.data(null);
		}
		return R.data(service.open(parseId(id)));
	}

	@GetMapping("/detail/{id}")
	@SaCheckPermission(AiConstants.PERM_DASHBOARD_DESIGN)
	public R<AiDashboard> detailPath(@PathVariable String id) {
		return R.data(service.open(parseId(id)));
	}

	@RepeatSubmit
	@PostMapping("/submit")
	@SaCheckPermission(AiConstants.PERM_DASHBOARD_DESIGN)
	public R<AiDashboard> submit(@RequestBody(required = false) AiDashboard body) {
		boolean create = body == null || body.getId() == null;
		AiDashboard saved = service.submit(body);
		R<AiDashboard> result = R.data(saved);
		result.setMsg(create
			? "已添加。可以继续改 DSL，保存后就能分享站内地址"
			: "已保存。再次打开会看到最新内容");
		return result;
	}

	@RepeatSubmit
	@PostMapping("/remove")
	@SaCheckPermission(AiConstants.PERM_DASHBOARD_DESIGN)
	public R<Void> remove(@RequestParam(required = false) String ids,
						 @RequestBody(required = false) Map<String, Object> body) {
		List<AiDashboard> removed = service.remove(parseIds(ids, body));
		if (removed.size() == 1) {
			return R.success("已删除「" + removed.get(0).getName() + "」。问数里再进会是一份新的");
		}
		return R.success("已删除 " + removed.size() + " 个仪表盘");
	}

	@RepeatSubmit
	@PostMapping("/dsl/save")
	@SaCheckPermission(AiConstants.PERM_DASHBOARD_DESIGN)
	public R<AiDashboard> saveDsl(@RequestBody(required = false) Map<String, Object> body) {
		if (body == null || body.get("id") == null || String.valueOf(body.get("id")).isBlank()) {
			throw new ServiceException("请先保存仪表盘，再改 DSL");
		}
		Object raw = body.get("dslJson") != null ? body.get("dslJson") : body.get("dsl");
		AiDashboard saved = service.saveDsl(parseId(body.get("id")),
			body.get("name") == null ? null : String.valueOf(body.get("name")),
			raw == null ? null : String.valueOf(raw));
		R<AiDashboard> result = R.data(saved);
		result.setMsg("已保存。再次打开会看到最新内容");
		return result;
	}

	@PostMapping("/share")
	@SaCheckPermission(AiConstants.PERM_DASHBOARD_DESIGN)
	public R<Map<String, Object>> share(@RequestBody(required = false) Map<String, Object> body) {
		if (body == null || body.get("id") == null || String.valueOf(body.get("id")).isBlank()) {
			throw new ServiceException("请先保存仪表盘，再分享");
		}
		Map<String, Object> data = service.share(parseId(body.get("id")));
		R<Map<String, Object>> result = R.data(data);
		result.setMsg(String.valueOf(data.get("message")));
		return result;
	}

	private static List<Long> parseIds(String ids, Map<String, Object> body) {
		String raw = ids;
		if ((raw == null || raw.isBlank()) && body != null) {
			Object value = body.get("ids") != null ? body.get("ids") : body.get("id");
			raw = value == null ? null : String.valueOf(value);
		}
		if (raw == null || raw.isBlank() || "null".equals(raw)) {
			throw new ServiceException("请先选择要删除的仪表盘");
		}
		List<Long> idList = new ArrayList<>();
		for (String part : raw.split(",")) {
			if (part == null || part.isBlank()) {
				continue;
			}
			idList.add(parseId(part));
		}
		if (idList.isEmpty()) {
			throw new ServiceException("请先选择要删除的仪表盘");
		}
		return idList;
	}

	private static Long parseId(Object raw) {
		String text = String.valueOf(raw).trim();
		if (!text.matches("\\d{1,19}")) {
			throw new ServiceException("仪表盘编号请填写数字，可以从地址里复制");
		}
		return Long.valueOf(text);
	}
}
