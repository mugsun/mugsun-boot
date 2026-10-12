package com.mugsun.boot.feedback;

import cn.dev33.satoken.annotation.SaCheckLogin;
import cn.dev33.satoken.annotation.SaCheckPermission;
import cn.dev33.satoken.stp.StpUtil;
import com.mugsun.boot.common.repeat.RepeatSubmit;
import com.mugsun.boot.feedback.entity.SysFeedback;
import com.mugsun.boot.feedback.mapper.SysFeedbackMapper;
import com.mugsun.boot.system.entity.SysAttach;
import com.mugsun.boot.system.service.AttachService;
import com.mugsun.core.tool.api.R;
import com.mugsun.core.tool.exception.ServiceException;
import com.mybatisflex.core.paginate.Page;
import com.mybatisflex.core.query.QueryWrapper;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.List;

/**
 * 意见反馈：任意登录用户提交（可带附件），管理员查看与处理。
 */
@RestController
@RequestMapping("/system/feedback")
@SaCheckLogin
public class FeedbackController {

	private final SysFeedbackMapper feedbackMapper;
	private final AttachService attachService;

	public FeedbackController(SysFeedbackMapper feedbackMapper, AttachService attachService) {
		this.feedbackMapper = feedbackMapper;
		this.attachService = attachService;
	}

	/** 用户提交反馈 */
	@PostMapping("/submit")
	@RepeatSubmit
	public R<Void> submit(@RequestBody SysFeedback feedback) {
		if (feedback == null) {
			throw new ServiceException("请填写反馈内容。写清楚遇到的问题和期望，再提交");
		}
		String content = feedback.getContent() == null ? "" : feedback.getContent().trim();
		if (content.isEmpty()) {
			throw new ServiceException("请填写反馈内容。写清楚遇到的问题和期望，再提交");
		}
		if (content.length() > 2000) {
			throw new ServiceException("反馈内容请控制在 2000 个字以内");
		}
		String contact = feedback.getContact() == null ? "" : feedback.getContact().trim();
		if (contact.length() > 64) {
			throw new ServiceException("联系方式请控制在 64 个字以内");
		}
		feedback.setContent(content);
		feedback.setContact(contact.isEmpty() ? null : contact);
		if (feedback.getAttachId() != null) {
			SysAttach attach = attachService.getById(feedback.getAttachId());
			if (attach == null) {
				throw new ServiceException("这个附件不存在。请重新选择图片后再提交");
			}
			feedback.setAttachName(attach.getName());
			feedback.setAttachUrl(attach.getUrl());
		} else {
			feedback.setAttachName(null);
			feedback.setAttachUrl(null);
		}
		feedback.setId(null);
		feedback.setUserId(StpUtil.getLoginIdAsLong());
		feedback.setStatus(0);
		feedback.sanitizeForInsert();
		feedbackMapper.insert(feedback);
		return R.success("已提交。管理员可以在意见反馈里看到，处理完会标成已处理");
	}

	/** 后台分页列表 */
	@GetMapping("/page")
	@SaCheckPermission("sys:feedback:manage")
	public R<Page<SysFeedback>> page(@RequestParam(defaultValue = "1") long pageNum,
									 @RequestParam(defaultValue = "10") long pageSize,
									 @RequestParam(required = false) Integer status,
									 @RequestParam(required = false) String name) {
		QueryWrapper query = QueryWrapper.create().orderBy("id", false);
		if (status != null) {
			if (status != 0 && status != 1) {
				throw new ServiceException("状态请选择未处理或已处理");
			}
			query.eq("status", status);
		}
		if (name != null && !name.isBlank()) {
			query.like("content", name.trim());
		}
		return R.data(feedbackMapper.paginate(pageNum, pageSize, query));
	}

	/** 切换处理状态 */
	@PostMapping("/status/{id}")
	@SaCheckPermission("sys:feedback:manage")
	@RepeatSubmit
	public R<Void> status(@PathVariable Long id) {
		SysFeedback feedback = feedbackMapper.selectOneById(id);
		if (feedback == null) {
			throw new ServiceException("这条反馈不存在。请刷新列表后再处理");
		}
		boolean handled = feedback.getStatus() != null && feedback.getStatus() == 1;
		feedback.setStatus(handled ? 0 : 1);
		feedbackMapper.update(feedback);
		if (handled) {
			return R.success("已标为未处理。还可以再标成已处理");
		}
		return R.success("已标为已处理。列表里的状态会跟着变");
	}

	@PostMapping("/remove")
	@SaCheckPermission("sys:feedback:manage")
	@RepeatSubmit
	public R<Void> remove(@RequestBody List<Long> ids) {
		if (ids == null || ids.isEmpty()) {
			throw new ServiceException("请先选择要删除的反馈");
		}
		if (ids.stream().anyMatch(id -> id == null)) {
			throw new ServiceException("有反馈不存在。请刷新列表后再删除");
		}
		List<SysFeedback> rows = feedbackMapper.selectListByIds(ids);
		if (rows.size() != ids.size()) {
			throw new ServiceException("有反馈不存在，或已经被删除。请刷新列表后再试");
		}
		List<Long> attachIds = new ArrayList<>();
		for (SysFeedback row : rows) {
			feedbackMapper.deleteById(row.getId());
			if (row.getAttachId() != null) {
				attachIds.add(row.getAttachId());
			}
		}
		if (!attachIds.isEmpty()) {
			attachService.deleteBatchByIds(attachIds);
		}
		return R.success("已删除 " + rows.size() + " 条反馈。列表里不会再出现");
	}
}
