package com.mugsun.boot.track.controller;

import cn.dev33.satoken.annotation.SaCheckLogin;
import cn.dev33.satoken.annotation.SaCheckPermission;
import com.mugsun.boot.common.constant.TrackConstants;
import com.mugsun.boot.system.entity.SysUser;
import com.mugsun.boot.system.mapper.SysUserMapper;
import com.mugsun.boot.track.TrackErrorIssueService;
import com.mugsun.core.tool.api.R;
import com.mugsun.core.tool.exception.ServiceException;
import com.mybatisflex.core.paginate.Page;
import com.mybatisflex.core.query.QueryWrapper;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 错误问题单 API（/system/track/error-issues/**）：列表/详情/指派/解决/重开。
 * <p>读权复用 {@link TrackConstants#PERM_ERROR_LIST}；写权
 * {@link TrackConstants#PERM_ERROR_ISSUE_EDIT}。
 * <p><b>分层纪律</b>：权限与业务库用户名解析留本层；问题单读写委托
 * {@link TrackErrorIssueService}（@TrackDS）。
 */
@RestController
@RequestMapping("/system/track/error-issues")
@SaCheckLogin
public class TrackErrorIssueController {

	private final TrackErrorIssueService issueService;
	private final SysUserMapper userMapper;

	public TrackErrorIssueController(TrackErrorIssueService issueService, SysUserMapper userMapper) {
		this.issueService = issueService;
		this.userMapper = userMapper;
	}

	@GetMapping("/page")
	@SaCheckPermission(TrackConstants.PERM_ERROR_LIST)
	public R<Page<Map<String, Object>>> page(@RequestParam(required = false) String appKey,
											 @RequestParam(required = false) String release,
											 @RequestParam(required = false) String status,
											 @RequestParam(required = false) Long assigneeId,
											 @RequestParam(required = false) Boolean regressedOnly,
											 @RequestParam(defaultValue = "1") long pageNum,
											 @RequestParam(defaultValue = "10") long pageSize) {
		Page<Map<String, Object>> page = issueService.page(appKey, release, status, assigneeId,
			regressedOnly, pageNum, pageSize);
		fillAssigneeNames(page.getRecords());
		return R.data(page);
	}

	@GetMapping("/detail")
	@SaCheckPermission(TrackConstants.PERM_ERROR_LIST)
	public R<Map<String, Object>> detail(@RequestParam(required = false) Long id,
										 @RequestParam(required = false) String appKey,
										 @RequestParam(required = false) String fingerprint) {
		Map<String, Object> data = issueService.detail(id, appKey, fingerprint);
		fillAssigneeNames(List.of(data));
		return R.data(data);
	}

	@PostMapping("/assign")
	@SaCheckPermission(TrackConstants.PERM_ERROR_ISSUE_EDIT)
	public R<Map<String, Object>> assign(@RequestBody(required = false) Map<String, Object> body) {
		if (body == null) {
			throw new ServiceException("请选择要指派的问题单");
		}
		Long id = longOf(body.get("id"));
		if (id == null) {
			throw new ServiceException("请选择要指派的问题单");
		}
		String appKey = strOf(body.get("appKey"));
		Long assigneeId = longOf(body.get("assigneeId"));
		Map<String, Object> data = issueService.assign(id, appKey, assigneeId);
		fillAssigneeNames(List.of(data));
		return R.data(data);
	}

	@PostMapping("/resolve")
	@SaCheckPermission(TrackConstants.PERM_ERROR_ISSUE_EDIT)
	public R<Map<String, Object>> resolve(@RequestBody(required = false) Map<String, Object> body) {
		Long id = body == null ? null : longOf(body.get("id"));
		if (id == null) {
			throw new ServiceException("请选择要标记已解决的问题单");
		}
		String appKey = body == null ? null : strOf(body.get("appKey"));
		Map<String, Object> data = issueService.resolve(id, appKey);
		fillAssigneeNames(List.of(data));
		return R.data(data);
	}

	@PostMapping("/reopen")
	@SaCheckPermission(TrackConstants.PERM_ERROR_ISSUE_EDIT)
	public R<Map<String, Object>> reopen(@RequestBody(required = false) Map<String, Object> body) {
		Long id = body == null ? null : longOf(body.get("id"));
		if (id == null) {
			throw new ServiceException("请选择要重开的问题单");
		}
		String appKey = body == null ? null : strOf(body.get("appKey"));
		Map<String, Object> data = issueService.reopen(id, appKey);
		fillAssigneeNames(List.of(data));
		return R.data(data);
	}

	/** 批量补 assigneeName（业务库查询，不进 @TrackDS） */
	private void fillAssigneeNames(List<Map<String, Object>> records) {
		if (records == null || records.isEmpty()) {
			return;
		}
		Set<Long> ids = new HashSet<>();
		for (Map<String, Object> row : records) {
			Long aid = longOf(row.get("assigneeId"));
			if (aid != null) {
				ids.add(aid);
			}
		}
		if (ids.isEmpty()) {
			return;
		}
		List<SysUser> users = userMapper.selectListByQuery(QueryWrapper.create().in("id", ids));
		Map<Long, String> names = new HashMap<>();
		for (SysUser u : users) {
			String name = u.getNickname();
			if (name == null || name.isBlank()) {
				name = u.getUsername();
			}
			names.put(u.getId(), name);
		}
		for (Map<String, Object> row : records) {
			Long aid = longOf(row.get("assigneeId"));
			if (aid != null) {
				row.put("assigneeName", names.get(aid));
			}
		}
	}

	private static String strOf(Object value) {
		if (value == null) {
			return null;
		}
		String s = value.toString().trim();
		return s.isEmpty() || "null".equalsIgnoreCase(s) ? null : s;
	}

	private static Long longOf(Object value) {
		if (value == null) {
			return null;
		}
		if (value instanceof Number n) {
			return n.longValue();
		}
		String s = value.toString().trim();
		if (s.isEmpty() || "null".equalsIgnoreCase(s)) {
			return null;
		}
		try {
			return Long.parseLong(s);
		} catch (NumberFormatException e) {
			return null;
		}
	}
}
