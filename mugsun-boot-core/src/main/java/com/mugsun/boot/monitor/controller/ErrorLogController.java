package com.mugsun.boot.monitor.controller;

import cn.dev33.satoken.annotation.SaCheckLogin;
import cn.dev33.satoken.annotation.SaCheckPermission;
import cn.dev33.satoken.stp.StpUtil;
import com.fasterxml.jackson.databind.JsonNode;
import com.mugsun.boot.common.constant.MonitorConstants;
import com.mugsun.boot.monitor.OperatorNameLookup;
import com.mugsun.boot.monitor.entity.SysErrorLog;
import com.mugsun.boot.monitor.mapper.SysErrorLogMapper;
import com.mugsun.core.tool.api.R;
import com.mugsun.core.tool.exception.ServiceException;
import com.mybatisflex.core.paginate.Page;
import com.mybatisflex.core.query.QueryWrapper;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;

/**
 * 错误日志管理：未捕获异常流水的查询与处理闭环（未处理 → 已处理/已忽略认领）。
 */
@RestController
@RequestMapping("/system/error-log")
@SaCheckLogin
public class ErrorLogController {

	private final SysErrorLogMapper errorLogMapper;
	private final OperatorNameLookup operatorNameLookup;

	public ErrorLogController(SysErrorLogMapper errorLogMapper, OperatorNameLookup operatorNameLookup) {
		this.errorLogMapper = errorLogMapper;
		this.operatorNameLookup = operatorNameLookup;
	}

	@GetMapping("/page")
	@SaCheckPermission(MonitorConstants.PERM_ERROR_LOG_LIST)
	public R<Page<SysErrorLog>> page(@RequestParam(defaultValue = "1") long pageNum,
									 @RequestParam(defaultValue = "10") long pageSize,
									 @RequestParam(required = false) Integer status,
									 @RequestParam(required = false) String traceId) {
		QueryWrapper query = QueryWrapper.create().orderBy("id", false);
		if (status != null) {
			query.and("status = ?", status);
		}
		if (traceId != null && !traceId.isBlank()) {
			query.and("trace_id = ?", traceId);
		}
		Page<SysErrorLog> page = errorLogMapper.paginate(pageNum, Math.min(pageSize, 500), query);
		fillOperatorName(page.getRecords());
		return R.data(page);
	}

	@GetMapping("/detail")
	@SaCheckPermission(MonitorConstants.PERM_ERROR_LOG_LIST)
	public R<SysErrorLog> detail(@RequestParam Long id) {
		SysErrorLog row = errorLogMapper.selectOneById(id);
		if (row != null) {
			fillOperatorName(java.util.List.of(row));
		}
		return R.data(row);
	}

	private void fillOperatorName(java.util.List<SysErrorLog> records) {
		if (records == null || records.isEmpty()) {
			return;
		}
		java.util.List<String> ids = new java.util.ArrayList<>();
		for (SysErrorLog row : records) {
			ids.add(row.getOperator());
			ids.add(row.getHandleUser());
		}
		java.util.Map<String, String> names = operatorNameLookup.namesOf(ids);
		for (SysErrorLog row : records) {
			String name = row.getOperator() == null ? null : names.get(row.getOperator());
			row.setOperatorName(name != null ? name : row.getOperator());
			String handler = row.getHandleUser() == null ? null : names.get(row.getHandleUser());
			row.setHandleUserName(handler != null ? handler : row.getHandleUser());
		}
	}

	/** 认领处理：状态流转 0 → 1（已处理）/ 2（已忽略），携带处理备注与认领人留痕 */
	@PostMapping("/handle")
	@SaCheckPermission(MonitorConstants.PERM_ERROR_LOG_HANDLE)
	public R<Void> handle(@RequestBody(required = false) JsonNode body) {
		if (body == null || !body.isObject()) {
			throw new ServiceException("请先在列表里选择要处理的错误日志");
		}
		JsonNode idNode = body.get("id");
		if (idNode == null || idNode.isNull() || idNode.asText("").isBlank()) {
			throw new ServiceException("请先在列表里选择要处理的错误日志");
		}
		String idText = idNode.asText().trim();
		if (!idText.matches("\\d{1,19}")) {
			throw new ServiceException("日志编号请填写数字，可以从列表里复制");
		}
		JsonNode statusNode = body.get("status");
		if (statusNode == null || statusNode.isNull() || statusNode.asText("").isBlank()) {
			throw new ServiceException("请选择已处理或已忽略");
		}
		String statusText = statusNode.asText().trim();
		if (!"1".equals(statusText) && !"2".equals(statusText)) {
			throw new ServiceException("请选择已处理或已忽略");
		}
		int status = Integer.parseInt(statusText);
		String note = text(body, "note");
		if (note != null) {
			note = note.trim();
			if (note.isEmpty()) {
				note = null;
			} else if (note.length() > 512) {
				throw new ServiceException("备注请控制在 512 个字以内");
			}
		}
		long id = Long.parseLong(idText);
		SysErrorLog record = errorLogMapper.selectOneById(id);
		if (record == null) {
			throw new ServiceException("这条错误日志不存在。请刷新列表后再试");
		}
		if (record.getStatus() != null && record.getStatus() != 0) {
			if (Integer.valueOf(MonitorConstants.ERROR_STATUS_IGNORED).equals(record.getStatus())) {
				throw new ServiceException("这条错误日志已经忽略。请刷新列表后再看");
			}
			throw new ServiceException("这条错误日志已经处理过。请刷新列表后再看");
		}
		SysErrorLog update = new SysErrorLog();
		update.setId(id);
		update.setStatus(status);
		update.setHandleUser(StpUtil.getLoginIdAsString());
		update.setHandleNote(note);
		update.setHandleTime(LocalDateTime.now());
		errorLogMapper.update(update);
		String label = labelOf(record);
		if (status == MonitorConstants.ERROR_STATUS_IGNORED) {
			return R.success("已忽略「" + label + "」。列表里会标成已忽略，不再等处理");
		}
		return R.success("已处理「" + label + "」。列表里会标成已处理");
	}

	private static String text(JsonNode body, String field) {
		JsonNode node = body.get(field);
		if (node == null || node.isNull()) {
			return null;
		}
		return node.asText();
	}

	@DeleteMapping("/remove")
	@SaCheckPermission(MonitorConstants.PERM_ERROR_LOG_REMOVE)
	public R<Void> remove(@RequestParam(required = false) String id) {
		if (id == null || id.isBlank()) {
			throw new ServiceException("请先在列表里选择要删除的错误日志");
		}
		String text = id.trim();
		if (!text.matches("\\d{1,19}")) {
			throw new ServiceException("日志编号请填写数字，可以从列表里复制");
		}
		SysErrorLog row = errorLogMapper.selectOneById(Long.parseLong(text));
		if (row == null) {
			throw new ServiceException("这条错误日志不存在。请刷新列表后再试");
		}
		errorLogMapper.deleteById(row.getId());
		return R.success("已删除「" + labelOf(row) + "」。列表里不会再出现这条记录");
	}

	private static String labelOf(SysErrorLog row) {
		String method = row.getRequestMethod() == null ? "" : row.getRequestMethod().trim();
		String uri = row.getRequestUri() == null ? "" : row.getRequestUri().trim();
		String label = (method + " " + uri).trim();
		if (label.isEmpty()) {
			String type = row.getExceptionClass() == null ? "" : row.getExceptionClass().trim();
			int dot = type.lastIndexOf('.');
			label = dot >= 0 ? type.substring(dot + 1) : type;
		}
		if (label.isEmpty()) {
			return "这条错误日志";
		}
		if (label.length() > 80) {
			return label.substring(0, 80);
		}
		return label;
	}
}
