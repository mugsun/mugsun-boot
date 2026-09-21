package com.mugsun.boot.mail;

import cn.dev33.satoken.annotation.SaCheckLogin;
import cn.dev33.satoken.annotation.SaCheckPermission;
import com.mugsun.boot.mail.entity.SysMailTemplate;
import com.mugsun.boot.mail.mapper.SysMailTemplateMapper;
import com.mugsun.core.tool.api.R;
import com.mybatisflex.core.paginate.Page;
import com.mybatisflex.core.query.QueryWrapper;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * 邮件模板管理 + 发送测试。
 */
@RestController
@RequestMapping("/system/mail-template")
@SaCheckLogin
public class MailTemplateController {

	private final SysMailTemplateMapper mapper;
	private final MailService mailService;

	public MailTemplateController(SysMailTemplateMapper mapper, MailService mailService) {
		this.mapper = mapper;
		this.mailService = mailService;
	}

	@GetMapping("/page")
	public R<Page<SysMailTemplate>> page(@RequestParam(defaultValue = "1") long pageNum,
										 @RequestParam(defaultValue = "10") long pageSize) {
		return R.data(mapper.paginate(pageNum, pageSize, QueryWrapper.create().orderBy("create_time", false)));
	}

	@GetMapping("/detail")
	public R<SysMailTemplate> detail(@RequestParam Long id) {
		return R.data(mapper.selectOneById(id));
	}

	@SaCheckPermission("sys:mail-template:save")
	@PostMapping("/submit")
	public R<Void> submit(@RequestBody SysMailTemplate template) {
		if (template.getId() == null) {
			mapper.insert(template);
		} else {
			mapper.update(template);
		}
		return R.success("操作成功");
	}

	@SaCheckPermission("sys:mail-template:remove")
	@PostMapping("/remove")
	public R<Void> remove(@RequestBody List<Long> ids) {
		mapper.deleteBatchByIds(ids);
		return R.success("删除成功");
	}

	/** 发送测试：按模板渲染并发送到指定邮箱（无 SMTP 凭证时降级日志），返回渲染内容 */
	@SaCheckPermission("sys:mail-template:send")
	@PostMapping("/send-test")
	public R<String> sendTest(@RequestBody Map<String, String> body) {
		String code = body.get("code");
		String to = body.getOrDefault("to", "test@example.com");
		String content = mailService.sendByTemplate(to, code, Map.of("code", "888888"));
		return R.data(content);
	}
}
