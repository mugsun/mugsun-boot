package com.mugsun.boot.message;

import cn.dev33.satoken.annotation.SaCheckLogin;
import cn.dev33.satoken.annotation.SaCheckPermission;
import com.mugsun.boot.message.entity.SysMessageTemplate;
import com.mugsun.boot.message.mapper.SysMessageTemplateMapper;
import com.mugsun.core.tool.api.R;
import com.mybatisflex.core.paginate.Page;
import com.mybatisflex.core.query.QueryWrapper;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 站内信模板：管理员维护，发送页选用。
 */
@RestController
@RequestMapping("/system/message-template")
@SaCheckLogin
public class MessageTemplateController {

	private final SysMessageTemplateMapper templateMapper;

	public MessageTemplateController(SysMessageTemplateMapper templateMapper) {
		this.templateMapper = templateMapper;
	}

	/** 全部模板（发送页下拉选用） */
	@GetMapping("/list")
	public R<List<SysMessageTemplate>> list() {
		return R.data(templateMapper.selectListByQuery(QueryWrapper.create().orderBy("id", false)));
	}

	@GetMapping("/page")
	public R<Page<SysMessageTemplate>> page(@RequestParam(defaultValue = "1") long pageNum,
											@RequestParam(defaultValue = "10") long pageSize) {
		return R.data(templateMapper.paginate(pageNum, pageSize, QueryWrapper.create().orderBy("id", false)));
	}

	@GetMapping("/detail")
	public R<SysMessageTemplate> detail(@RequestParam Long id) {
		return R.data(templateMapper.selectOneById(id));
	}

	@PostMapping("/submit")
	@SaCheckPermission("sys:message:manage")
	public R<Void> submit(@RequestBody SysMessageTemplate template) {
		if (template.getId() == null) {
			templateMapper.insertSelective(template);
		} else {
			templateMapper.update(template);
		}
		return R.success("操作成功");
	}

	@PostMapping("/remove")
	@SaCheckPermission("sys:message:manage")
	public R<Void> remove(@RequestBody List<Long> ids) {
		templateMapper.deleteBatchByIds(ids);
		return R.success("删除成功");
	}
}
