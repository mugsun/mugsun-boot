package com.mugsun.boot.log.controller;

import cn.dev33.satoken.annotation.SaCheckLogin;
import com.mugsun.boot.log.entity.SysOperLog;
import com.mugsun.boot.log.mapper.SysOperLogMapper;
import com.mugsun.core.tool.api.R;
import com.mybatisflex.core.paginate.Page;
import com.mybatisflex.core.query.QueryWrapper;
import org.springframework.web.bind.annotation.*;

/**
 * 操作日志查询（含错误日志：按 status 区分成功/失败）
 */
@RestController
@RequestMapping("/system/oper-log")
@SaCheckLogin
public class SysOperLogController {

	private final SysOperLogMapper operLogMapper;
	private final com.mugsun.boot.log.OperationLogService operationLogService;
	private final com.mugsun.boot.system.mapper.SysUserMapper userMapper;

	public SysOperLogController(SysOperLogMapper operLogMapper,
								com.mugsun.boot.log.OperationLogService operationLogService,
								com.mugsun.boot.system.mapper.SysUserMapper userMapper) {
		this.operLogMapper = operLogMapper;
		this.operationLogService = operationLogService;
		this.userMapper = userMapper;
	}

	@GetMapping("/page")
	@cn.dev33.satoken.annotation.SaCheckPermission("sys:oper-log:list")
	public R<Page<SysOperLog>> page(@RequestParam(defaultValue = "1") long pageNum,
									@RequestParam(defaultValue = "10") long pageSize,
									@RequestParam(required = false) String title,
									@RequestParam(required = false) String operator,
									@RequestParam(required = false) Integer status,
									@RequestParam(required = false) String beginTime,
									@RequestParam(required = false) String endTime) {
		QueryWrapper query = QueryWrapper.create().orderBy("id", false);
		// 查询条件（值走参数化绑定，LIKE 前后模糊）
		if (title != null && !title.isBlank()) {
			query.like("title", title.trim());
		}
		if (operator != null && !operator.isBlank()) {
			// 操作人按用户名/昵称模糊解析为 id 集再过滤（库内 operator 存用户 id 非姓名）；无匹配即空页
			java.util.List<String> operatorIds = userMapper.selectListByQuery(QueryWrapper.create()
				.and("(username LIKE ? OR nickname LIKE ?)", likeOf(operator), likeOf(operator)))
				.stream().map(u -> String.valueOf(u.getId())).toList();
			if (operatorIds.isEmpty()) {
				return R.data(new Page<>(java.util.List.of(), pageNum, Math.min(pageSize, 500), 0));
			}
			query.in("operator", operatorIds);
		}
		if (status != null) {
			query.and("status = ?", status);
		}
		// 时间范围（create_time；前端 datetimerange 直传 'yyyy-MM-dd HH:mm:ss'，解析失败即参数错误）
		if (beginTime != null && !beginTime.isBlank()) {
			query.ge("create_time", parseTime(beginTime));
		}
		if (endTime != null && !endTime.isBlank()) {
			query.le("create_time", parseTime(endTime));
		}
		Page<SysOperLog> page = operLogMapper.paginate(pageNum, Math.min(pageSize, 500), query);
		enrichOperatorName(page.getRecords());
		return R.data(page);
	}

	/** 操作人显示名富化：批量解析 id→昵称(用户名兜底)，用户已删回退原 id（防 N+1） */
	private void enrichOperatorName(java.util.List<SysOperLog> records) {
		if (records == null || records.isEmpty()) {
			return;
		}
		java.util.Set<Long> ids = new java.util.HashSet<>();
		for (SysOperLog r : records) {
			if (r.getOperator() != null && r.getOperator().matches("\\d+")) {
				ids.add(Long.valueOf(r.getOperator()));
			}
		}
		java.util.Map<Long, String> nameMap = new java.util.HashMap<>();
		if (!ids.isEmpty()) {
			userMapper.selectListByQuery(QueryWrapper.create().in("id", ids)).forEach(u -> nameMap.put(
				u.getId(), u.getNickname() == null || u.getNickname().isBlank() ? u.getUsername() : u.getNickname()));
		}
		for (SysOperLog r : records) {
			String name = r.getOperator() != null && r.getOperator().matches("\\d+")
				? nameMap.get(Long.valueOf(r.getOperator()))
				: null;
			r.setOperatorName(name != null ? name : r.getOperator());
		}
	}

	/** 查询时间解析：'yyyy-MM-dd HH:mm:ss' → LocalDateTime（与库列类型对齐，防驱动类型推断差异） */
	private java.time.LocalDateTime parseTime(String raw) {
		try {
			return java.time.LocalDateTime.parse(raw.trim(),
				java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
		} catch (java.time.format.DateTimeParseException e) {
			throw new com.mugsun.core.tool.exception.ServiceException("时间格式不正确");
		}
	}

	/** LIKE 值转义：%/_ 按字面处理（防通配符注入扭曲匹配语义） */
	private String likeOf(String raw) {
		String v = raw.trim().replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
		return "%" + v + "%";
	}

	@GetMapping("/detail")
	@cn.dev33.satoken.annotation.SaCheckPermission("sys:oper-log:list")
	public R<SysOperLog> detail(@RequestParam Long id) {
		return R.data(operLogMapper.selectOneById(id));
	}

	/** 审计完整性验签：重算哈希链 + 验证 SM2 签名，检出篡改并定位首个被篡改记录。
	 *  仅管理员可触发（防任意用户反复触发全量验签 DoS）；limit&gt;0 只校最近 N 条（有界内存）。 */
	@cn.dev33.satoken.annotation.SaCheckPermission("sys:oper-log:verify")
	@GetMapping("/verify")
	public R<java.util.Map<String, Object>> verify(@RequestParam(required = false) Integer limit) {
		return R.data(operationLogService.verify(limit));
	}
}
