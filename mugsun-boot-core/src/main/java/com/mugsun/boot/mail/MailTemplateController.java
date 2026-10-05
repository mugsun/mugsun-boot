package com.mugsun.boot.mail;

import cn.dev33.satoken.annotation.SaCheckLogin;
import cn.dev33.satoken.annotation.SaCheckPermission;
import com.mugsun.boot.common.repeat.RepeatSubmit;
import com.mugsun.boot.mail.entity.SysMailTemplate;
import com.mugsun.boot.mail.mapper.SysMailTemplateMapper;
import com.mugsun.core.tool.api.R;
import com.mugsun.core.tool.exception.ServiceException;
import com.mybatisflex.core.paginate.Page;
import com.mybatisflex.core.query.QueryWrapper;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 邮件模板管理 + 发送测试。
 */
@RestController
@RequestMapping("/system/mail-template")
@SaCheckLogin
public class MailTemplateController {

	private static final Set<String> BUILTIN = Set.of("login_2fa", "forget_password");

	private final SysMailTemplateMapper mapper;
	private final MailService mailService;

	public MailTemplateController(SysMailTemplateMapper mapper, MailService mailService) {
		this.mapper = mapper;
		this.mailService = mailService;
	}

	@GetMapping("/page")
	@SaCheckPermission("sys:mail-template:list")
	public R<Page<SysMailTemplate>> page(@RequestParam(defaultValue = "1") long pageNum,
										 @RequestParam(defaultValue = "10") long pageSize,
										 @RequestParam(required = false) String name,
										 @RequestParam(required = false) String code) {
		QueryWrapper query = QueryWrapper.create();
		if (name != null && !name.isBlank()) {
			query.like("name", name.trim());
		}
		if (code != null && !code.isBlank()) {
			query.like("code", code.trim());
		}
		query.orderBy("create_time", false);
		return R.data(mapper.paginate(pageNum, pageSize, query));
	}

	@GetMapping("/detail")
	@SaCheckPermission("sys:mail-template:list")
	public R<SysMailTemplate> detail(@RequestParam Long id) {
		SysMailTemplate template = mapper.selectOneById(id);
		if (template == null) {
			throw new ServiceException("这个模板不存在。请刷新列表后再看");
		}
		return R.data(template);
	}

	@SaCheckPermission("sys:mail-template:save")
	@RepeatSubmit
	@PostMapping("/submit")
	public R<Void> submit(@RequestBody SysMailTemplate template) {
		if (template == null) {
			throw new ServiceException("请填写模板编码");
		}
		boolean creating = template.getId() == null;
		normalize(template);
		if (creating) {
			SysMailTemplate taken = mapper.selectOneByQuery(QueryWrapper.create().eq("code", template.getCode()));
			if (taken != null) {
				throw new ServiceException("模板编码「" + template.getCode() + "」已经有了。请换一个编码，或编辑原来那个");
			}
			mapper.insert(template);
			return R.success("已保存。列表里可以查看这个模板的主题和状态");
		}
		SysMailTemplate before = mapper.selectOneById(template.getId());
		if (before == null) {
			throw new ServiceException("这个模板不存在。请刷新列表后再编辑");
		}
		if (before.getCode() != null && !before.getCode().equals(template.getCode())) {
			throw new ServiceException("模板编码创建后不能修改。请新增一个模板，再删除原来的");
		}
		if (BUILTIN.contains(before.getCode()) && template.getStatus() != null && template.getStatus() == 0) {
			throw new ServiceException("「" + before.getName() + "」是内置邮件模板，不能停用。登录验证码和找回密码还要用它");
		}
		template.setCode(before.getCode());
		mapper.update(template);
		return R.success("已保存。列表里可以查看这个模板的主题和状态");
	}

	@SaCheckPermission("sys:mail-template:remove")
	@RepeatSubmit
	@PostMapping("/remove")
	public R<Void> remove(@RequestBody List<Long> ids) {
		if (ids == null || ids.isEmpty()) {
			throw new ServiceException("请先选择要删除的模板");
		}
		if (ids.stream().anyMatch(id -> id == null)) {
			throw new ServiceException("有模板不存在。请刷新列表后再删除");
		}
		List<SysMailTemplate> rows = mapper.selectListByIds(ids);
		if (rows.size() != ids.size()) {
			throw new ServiceException("有模板不存在，或已经被删除。请刷新列表后再试");
		}
		for (SysMailTemplate row : rows) {
			if (BUILTIN.contains(row.getCode())) {
				throw new ServiceException("「" + row.getName() + "」是内置邮件模板，不能删除。请改其他模板");
			}
		}
		for (SysMailTemplate row : rows) {
			mapper.deleteById(row.getId());
		}
		return R.success("已删除 " + rows.size() + " 个模板。列表里不会再出现");
	}

	/** 发送测试：按模板渲染并发送到指定邮箱。通道未配置时说明下一步，不返回连接原文。 */
	@SaCheckPermission("sys:mail-template:send")
	@RepeatSubmit
	@PostMapping("/send-test")
	public R<String> sendTest(@RequestBody Map<String, String> body) {
		if (body == null) {
			throw new ServiceException("请填写接收邮箱");
		}
		String code = body.get("code") == null ? "" : body.get("code").trim();
		String to = body.get("to") == null ? "" : body.get("to").trim();
		if (code.isEmpty()) {
			throw new ServiceException("请选择要测试的模板");
		}
		if (!to.matches("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$")) {
			throw new ServiceException("请填写正确的接收邮箱，例如 name@example.com");
		}
		String content = mailService.sendByTemplate(to, code, Map.of("code", "888888"));
		R<String> result = R.data(content);
		result.setMsg("测试邮件已按模板渲染。如果通道已配好，对方邮箱里应能看到这封信");
		return result;
	}

	private void normalize(SysMailTemplate template) {
		String code = template.getCode() == null ? "" : template.getCode().trim();
		if (code.isEmpty()) {
			throw new ServiceException("请填写模板编码");
		}
		if (!code.matches("^[a-z][a-z0-9_]{0,63}$")) {
			throw new ServiceException("模板编码请用小写字母开头，只能包含小写字母、数字和下划线");
		}
		String name = template.getName() == null ? "" : template.getName().trim();
		if (name.isEmpty()) {
			throw new ServiceException("请填写模板名称");
		}
		if (name.length() > 128) {
			throw new ServiceException("模板名称请控制在 128 个字以内");
		}
		String subject = template.getSubject() == null ? "" : template.getSubject().trim();
		if (subject.isEmpty()) {
			throw new ServiceException("请填写邮件主题");
		}
		if (subject.length() > 255) {
			throw new ServiceException("邮件主题请控制在 255 个字以内");
		}
		String content = template.getContent() == null ? "" : template.getContent();
		if (content.isBlank()) {
			throw new ServiceException("请填写邮件内容。需要验证码时写成 ${code}");
		}
		if (content.length() > 20000) {
			throw new ServiceException("邮件内容请控制在 20000 个字以内");
		}
		if (template.getStatus() == null) {
			template.setStatus(1);
		}
		if (template.getStatus() == null || (template.getStatus() != 0 && template.getStatus() != 1)) {
			throw new ServiceException("状态只能选择启用或停用");
		}
		template.setCode(code);
		template.setName(name);
		template.setSubject(subject);
		template.setContent(content);
	}
}
