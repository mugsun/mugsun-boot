package com.mugsun.boot.notify.controller;

import cn.dev33.satoken.annotation.SaCheckLogin;
import cn.dev33.satoken.annotation.SaCheckPermission;
import com.mugsun.boot.common.constant.NotifyConstants;
import com.mugsun.boot.common.repeat.RepeatSubmit;
import com.mugsun.boot.notify.NotifyTemplateRenderer;
import com.mugsun.boot.notify.entity.SysNotifyTemplate;
import com.mugsun.boot.notify.mapper.SysNotifyTemplateMapper;
import com.mugsun.core.tool.api.R;
import com.mugsun.core.tool.exception.ServiceException;
import com.mybatisflex.core.paginate.Page;
import com.mybatisflex.core.query.QueryWrapper;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 统一通知模板：保存时抽出 ${key} 占位。欢迎模板不能删、不能停用。
 */
@RestController
@RequestMapping("/system/notify/template")
@SaCheckLogin
public class SysNotifyTemplateController {

	private static final Pattern PLACEHOLDER = Pattern.compile("\\$\\{[A-Za-z][A-Za-z0-9_]{0,31}\\}");
	private static final Set<String> CHANNELS = Set.of(
		NotifyConstants.CHANNEL_IN_APP, NotifyConstants.CHANNEL_MAIL, NotifyConstants.CHANNEL_SMS);

	private final SysNotifyTemplateMapper templateMapper;
	private final NotifyTemplateRenderer renderer;

	public SysNotifyTemplateController(SysNotifyTemplateMapper templateMapper, NotifyTemplateRenderer renderer) {
		this.templateMapper = templateMapper;
		this.renderer = renderer;
	}

	@GetMapping("/page")
	@SaCheckPermission("sys:notify:list")
	public R<Page<SysNotifyTemplate>> page(@RequestParam(defaultValue = "1") long pageNum,
										   @RequestParam(defaultValue = "10") long pageSize,
										   @RequestParam(required = false) String name) {
		if (pageNum < 1 || pageSize < 1 || pageSize > 100) {
			throw new ServiceException("页码从 1 开始，每页最多 100 条");
		}
		QueryWrapper query = QueryWrapper.create();
		if (name != null && !name.isBlank()) {
			query.like("name", name.trim());
		}
		query.orderBy("id", false);
		return R.data(templateMapper.paginate(pageNum, pageSize, query));
	}

	@GetMapping("/detail")
	@SaCheckPermission("sys:notify:list")
	public R<SysNotifyTemplate> detail(@RequestParam(required = false) Long id) {
		if (id == null) {
			throw new ServiceException("请从列表中选择一条通知模板后再查看");
		}
		SysNotifyTemplate template = templateMapper.selectOneById(id);
		if (template == null) {
			throw new ServiceException("这个模板不存在。请刷新列表后再看");
		}
		return R.data(template);
	}

	@PostMapping("/submit")
	@SaCheckPermission("sys:notify:save")
	@RepeatSubmit
	public R<Void> submit(@RequestBody SysNotifyTemplate template) {
		if (template == null) {
			throw new ServiceException("请填写模板编码");
		}
		normalize(template);
		String params = String.join(NotifyConstants.SPLIT,
			renderer.extractParams(template.getSubject(), template.getContent()));
		if (params.length() > 255) {
			throw new ServiceException("占位太多。请减少 ${name} 这种占位后再保存");
		}
		template.setRequiredParams(params.isEmpty() ? null : params);
		if (template.getId() == null) {
			SysNotifyTemplate taken = templateMapper.selectOneByQuery(
				QueryWrapper.create().eq("code", template.getCode()));
			if (taken != null) {
				throw new ServiceException("模板编码「" + template.getCode() + "」已经有了。请换一个编码，或编辑原来那个");
			}
			templateMapper.insertSelective(template);
			return R.success("已保存。列表里可以查看这个模板的名称和渠道");
		}
		SysNotifyTemplate before = templateMapper.selectOneById(template.getId());
		if (before == null) {
			throw new ServiceException("这个模板不存在。请刷新列表后再编辑");
		}
		if (before.getCode() != null && !before.getCode().equals(template.getCode())) {
			throw new ServiceException("模板编码创建后不能修改。请新增一个模板，再删除原来的");
		}
		if (NotifyConstants.TEMPLATE_WELCOME.equals(before.getCode()) && template.getStatus() == 0) {
			throw new ServiceException("「" + before.getName() + "」是内置通知模板，不能停用。新建用户时还要用它");
		}
		template.setCode(before.getCode());
		templateMapper.update(template);
		return R.success("已保存。列表里可以查看这个模板的名称和渠道");
	}

	@PostMapping("/remove")
	@SaCheckPermission("sys:notify:remove")
	@RepeatSubmit
	public R<Void> remove(@RequestBody List<Long> ids) {
		if (ids == null || ids.isEmpty()) {
			throw new ServiceException("请先选择要删除的模板");
		}
		List<Long> distinct = new ArrayList<>();
		for (Long id : ids) {
			if (id == null) {
				throw new ServiceException("有模板不存在。请刷新列表后再删除");
			}
			if (!distinct.contains(id)) {
				distinct.add(id);
			}
		}
		List<SysNotifyTemplate> rows = templateMapper.selectListByIds(distinct);
		if (rows.size() != distinct.size()) {
			throw new ServiceException("有模板不存在，或已经被删除。请刷新列表后再试");
		}
		for (SysNotifyTemplate row : rows) {
			if (NotifyConstants.TEMPLATE_WELCOME.equals(row.getCode())) {
				throw new ServiceException("「" + row.getName() + "」是内置通知模板，不能删除。新建用户时还要用它");
			}
		}
		for (SysNotifyTemplate row : rows) {
			templateMapper.deleteById(row.getId());
		}
		return R.success("已删除 " + rows.size() + " 个模板。列表里不会再出现");
	}

	private void normalize(SysNotifyTemplate template) {
		String code = template.getCode() == null ? "" : template.getCode().trim();
		if (code.isEmpty()) {
			throw new ServiceException("请填写模板编码");
		}
		if (!code.matches("^[a-z][a-z0-9_]{0,63}$")) {
			throw new ServiceException("模板编码请用小写字母开头，只能包含小写字母、数字和下划线");
		}
		template.setCode(code);
		String name = template.getName() == null ? "" : template.getName().trim();
		if (name.isEmpty()) {
			throw new ServiceException("请填写模板名称");
		}
		if (name.length() > 128) {
			throw new ServiceException("模板名称请控制在 128 个字以内");
		}
		template.setName(name);
		String subject = template.getSubject() == null ? "" : template.getSubject().trim();
		if (subject.isEmpty()) {
			throw new ServiceException("请填写通知主题");
		}
		if (subject.length() > 255) {
			throw new ServiceException("通知主题请控制在 255 个字以内");
		}
		assertPlaceholders(subject, "主题");
		template.setSubject(subject);
		String content = template.getContent() == null ? "" : template.getContent().trim();
		if (content.isEmpty()) {
			throw new ServiceException("请填写通知内容");
		}
		if (content.length() > 20000) {
			throw new ServiceException("通知内容请控制在 20000 个字以内");
		}
		assertPlaceholders(content, "内容");
		template.setContent(content);
		template.setChannels(normalizeChannels(template.getChannels()));
		if (template.getStatus() == null || (template.getStatus() != 0 && template.getStatus() != 1)) {
			throw new ServiceException("状态只能选择启用或停用");
		}
		if (NotifyConstants.TEMPLATE_WELCOME.equals(code) && template.getStatus() == 0) {
			throw new ServiceException("「新用户欢迎通知」是内置通知模板，不能停用。新建用户时还要用它");
		}
		String remark = template.getRemark() == null ? "" : template.getRemark().trim();
		if (remark.length() > 255) {
			throw new ServiceException("备注请控制在 255 个字以内");
		}
		template.setRemark(remark.isEmpty() ? null : remark);
	}

	private String normalizeChannels(String raw) {
		String text = raw == null ? "" : raw.trim();
		if (text.isEmpty()) {
			throw new ServiceException("请选择投递渠道：站内信、邮件或短信，多项用英文逗号分隔");
		}
		LinkedHashSet<String> chosen = new LinkedHashSet<>();
		for (String part : text.split(",")) {
			String code = part.trim();
			if (code.isEmpty()) {
				continue;
			}
			if (!CHANNELS.contains(code)) {
				throw new ServiceException("投递渠道请从站内信 in_app、邮件 mail、短信 sms 里选。多项用英文逗号分隔");
			}
			chosen.add(code);
		}
		if (chosen.isEmpty()) {
			throw new ServiceException("请选择投递渠道：站内信、邮件或短信，多项用英文逗号分隔");
		}
		return String.join(NotifyConstants.SPLIT, chosen);
	}

	private void assertPlaceholders(String text, String field) {
		String left = PLACEHOLDER.matcher(text).replaceAll("");
		if (left.contains("${")) {
			throw new ServiceException(field + "里的占位请写成 ${name} 这种形式。名称用字母开头，发送时再填上对应的值");
		}
	}
}
