package com.mugsun.boot.feedback;

import cn.dev33.satoken.annotation.SaCheckLogin;
import cn.dev33.satoken.annotation.SaCheckPermission;
import cn.dev33.satoken.stp.StpUtil;
import com.mugsun.boot.feedback.entity.SysFeedback;
import com.mugsun.boot.feedback.mapper.SysFeedbackMapper;
import com.mugsun.core.tool.api.R;
import com.mugsun.core.tool.exception.ServiceException;
import com.mybatisflex.core.paginate.Page;
import com.mybatisflex.core.query.QueryWrapper;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 意见反馈：任意登录用户提交（可带附件），管理员查看与处理。
 */
@RestController
@RequestMapping("/system/feedback")
@SaCheckLogin
public class FeedbackController {

	private final SysFeedbackMapper feedbackMapper;
	private final com.mugsun.boot.system.mapper.SysAttachMapper attachMapper;

	public FeedbackController(SysFeedbackMapper feedbackMapper,
							  com.mugsun.boot.system.mapper.SysAttachMapper attachMapper) {
		this.feedbackMapper = feedbackMapper;
		this.attachMapper = attachMapper;
	}

	/** 用户提交反馈 */
	@PostMapping("/submit")
	public R<Void> submit(@RequestBody SysFeedback feedback) {
		if (feedback.getContent() == null || feedback.getContent().isBlank()) {
			throw new ServiceException("反馈内容不能为空");
		}
		// 附件信息一律按 attachId 服务端解析回填（不信任客户端传入的名称/地址——
		// 防 javascript: 伪协议等存储型 XSS 钓管理员；本地存储相对地址亦不受影响）
		if (feedback.getAttachId() != null) {
			com.mugsun.boot.system.entity.SysAttach attach = attachMapper.selectOneById(feedback.getAttachId());
			if (attach == null) {
				throw new ServiceException("附件不存在或已删除");
			}
			feedback.setAttachName(attach.getName());
			feedback.setAttachUrl(attach.getUrl());
		} else {
			feedback.setAttachName(null);
			feedback.setAttachUrl(null);
		}
		// 提交人与状态由服务端定，忽略客户端 id
		feedback.setId(null);
		feedback.setUserId(StpUtil.getLoginIdAsLong());
		feedback.setStatus(0);
		feedback.sanitizeForInsert();
		feedbackMapper.insertSelective(feedback);
		return R.success("提交成功");
	}

	/** 后台分页列表 */
	@GetMapping("/page")
	@SaCheckPermission("sys:feedback:manage")
	public R<Page<SysFeedback>> page(@RequestParam(defaultValue = "1") long pageNum,
									 @RequestParam(defaultValue = "10") long pageSize,
									 @RequestParam(required = false) Integer status) {
		QueryWrapper query = QueryWrapper.create().orderBy("id", false);
		if (status != null) {
			query.eq("status", status);
		}
		return R.data(feedbackMapper.paginate(pageNum, pageSize, query));
	}

	/** 切换处理状态 */
	@PostMapping("/status/{id}")
	@SaCheckPermission("sys:feedback:manage")
	public R<Void> status(@PathVariable Long id) {
		SysFeedback feedback = feedbackMapper.selectOneById(id);
		if (feedback != null) {
			feedback.setStatus(feedback.getStatus() != null && feedback.getStatus() == 1 ? 0 : 1);
			feedbackMapper.update(feedback);
		}
		return R.success("操作成功");
	}

	@PostMapping("/remove")
	@SaCheckPermission("sys:feedback:manage")
	public R<Void> remove(@RequestBody List<Long> ids) {
		feedbackMapper.deleteBatchByIds(ids);
		return R.success("删除成功");
	}
}
