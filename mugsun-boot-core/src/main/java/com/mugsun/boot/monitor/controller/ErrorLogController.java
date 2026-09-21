package com.mugsun.boot.monitor.controller;

import cn.dev33.satoken.annotation.SaCheckLogin;
import cn.dev33.satoken.annotation.SaCheckPermission;
import cn.dev33.satoken.stp.StpUtil;
import com.mugsun.boot.common.constant.MonitorConstants;
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
import java.util.Map;

/**
 * 错误日志管理：未捕获异常流水的查询与处理闭环（未处理 → 已处理/已忽略认领）。
 */
@RestController
@RequestMapping("/system/error-log")
@SaCheckLogin
public class ErrorLogController {

	private final SysErrorLogMapper errorLogMapper;

	public ErrorLogController(SysErrorLogMapper errorLogMapper) {
		this.errorLogMapper = errorLogMapper;
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
		return R.data(errorLogMapper.paginate(pageNum, Math.min(pageSize, 500), query));
	}

	@GetMapping("/detail")
	@SaCheckPermission(MonitorConstants.PERM_ERROR_LOG_LIST)
	public R<SysErrorLog> detail(@RequestParam Long id) {
		return R.data(errorLogMapper.selectOneById(id));
	}

	/** 认领处理：状态流转 0 → 1（已处理）/ 2（已忽略），携带处理备注与认领人留痕 */
	@PostMapping("/handle")
	@SaCheckPermission(MonitorConstants.PERM_ERROR_LOG_HANDLE)
	public R<Void> handle(@RequestBody Map<String, Object> body) {
		Long id = body.get("id") == null ? null : Long.valueOf(body.get("id").toString());
		Integer status = body.get("status") == null ? null : Integer.valueOf(body.get("status").toString());
		String note = body.get("note") == null ? null : body.get("note").toString();
		if (id == null) {
			throw new ServiceException("缺少 id");
		}
		if (status == null || (status != MonitorConstants.ERROR_STATUS_DONE
			&& status != MonitorConstants.ERROR_STATUS_IGNORED)) {
			throw new ServiceException("状态仅支持 1（已处理）/ 2（已忽略）");
		}
		SysErrorLog record = errorLogMapper.selectOneById(id);
		if (record == null) {
			throw new ServiceException("错误日志不存在");
		}
		// 乐观流转：仅待处理（0）可认领，已处理记录不被他人重复覆盖（留痕 handle_user/note 不被改写）
		if (record.getStatus() != null && record.getStatus() != 0) {
			throw new ServiceException("该记录已被处理");
		}
		SysErrorLog update = new SysErrorLog();
		update.setId(id);
		update.setStatus(status);
		update.setHandleUser(StpUtil.getLoginIdAsString());
		update.setHandleNote(note);
		update.setHandleTime(LocalDateTime.now());
		errorLogMapper.update(update);
		return R.success("已处理");
	}

	@DeleteMapping("/remove")
	@SaCheckPermission(MonitorConstants.PERM_ERROR_LOG_REMOVE)
	public R<Void> remove(@RequestParam Long id) {
		errorLogMapper.deleteById(id);
		return R.success("已删除");
	}
}
