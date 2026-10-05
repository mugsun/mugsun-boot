package com.mugsun.boot.message;

import cn.dev33.satoken.annotation.SaCheckLogin;
import cn.dev33.satoken.annotation.SaCheckPermission;
import com.mugsun.boot.common.repeat.RepeatSubmit;
import com.mugsun.boot.message.entity.SysMessageTemplate;
import com.mugsun.boot.message.mapper.SysMessageTemplateMapper;
import com.mugsun.core.tool.api.R;
import com.mugsun.core.tool.exception.ServiceException;
import com.mybatisflex.core.paginate.Page;
import com.mybatisflex.core.query.QueryWrapper;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 站内信模板：管理员维护，发送页选用。
 */
@RestController
@RequestMapping("/system/message-template")
@SaCheckLogin
public class MessageTemplateController {

	private static final Set<String> BUILTIN = Set.of("welcome");
	private static final Pattern PLACEHOLDER = Pattern.compile("\\$\\{[A-Za-z][A-Za-z0-9_]{0,31}\\}");

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
	@SaCheckPermission("sys:message:manage")
	public R<Page<SysMessageTemplate>> page(@RequestParam(defaultValue = "1") long pageNum,
											@RequestParam(defaultValue = "10") long pageSize,
											@RequestParam(required = false) String name,
											@RequestParam(required = false) String code) {
		QueryWrapper query = QueryWrapper.create();
		if (name != null && !name.isBlank()) {
			query.like("title", name.trim());
		}
		if (code != null && !code.isBlank()) {
			query.like("code", code.trim());
		}
		query.orderBy("id", false);
		return R.data(templateMapper.paginate(pageNum, pageSize, query));
	}

	@GetMapping("/detail")
	@SaCheckPermission("sys:message:manage")
	public R<SysMessageTemplate> detail(@RequestParam(required = false) Long id) {
		if (id == null) {
			throw new ServiceException("请从列表中选择一条消息模板后再查看");
		}
		SysMessageTemplate template = templateMapper.selectOneById(id);
		if (template == null) {
			throw new ServiceException("这个模板不存在。请刷新列表后再看");
		}
		return R.data(template);
	}

	@PostMapping("/submit")
	@SaCheckPermission("sys:message:manage")
	@RepeatSubmit
	public R<Void> submit(@RequestBody SysMessageTemplate template) {
		if (template == null) {
			throw new ServiceException("请填写模板编码");
		}
		boolean creating = template.getId() == null;
		normalize(template);
		if (creating) {
			SysMessageTemplate taken = templateMapper.selectOneByQuery(
				QueryWrapper.create().eq("code", template.getCode()));
			if (taken != null) {
				throw new ServiceException("模板编码「" + template.getCode() + "」已经有了。请换一个编码，或编辑原来那个");
			}
			templateMapper.insert(template);
			return R.success("已保存。列表里可以查看这个模板的标题");
		}
		SysMessageTemplate before = templateMapper.selectOneById(template.getId());
		if (before == null) {
			throw new ServiceException("这个模板不存在。请刷新列表后再编辑");
		}
		if (before.getCode() != null && !before.getCode().equals(template.getCode())) {
			throw new ServiceException("模板编码创建后不能修改。请新增一个模板，再删除原来的");
		}
		template.setCode(before.getCode());
		templateMapper.update(template);
		return R.success("已保存。列表里可以查看这个模板的标题");
	}

	@PostMapping("/remove")
	@SaCheckPermission("sys:message:manage")
	@RepeatSubmit
	public R<Void> remove(@RequestBody List<Long> ids) {
		if (ids == null || ids.isEmpty()) {
			throw new ServiceException("请先选择要删除的模板");
		}
		if (ids.stream().anyMatch(id -> id == null)) {
			throw new ServiceException("有模板不存在。请刷新列表后再删除");
		}
		List<SysMessageTemplate> rows = templateMapper.selectListByIds(ids);
		if (rows.size() != ids.size()) {
			throw new ServiceException("有模板不存在，或已经被删除。请刷新列表后再试");
		}
		for (SysMessageTemplate row : rows) {
			if (BUILTIN.contains(row.getCode())) {
				throw new ServiceException("「" + row.getTitle() + "」是内置消息模板，不能删除。新建用户时还要用它");
			}
		}
		for (SysMessageTemplate row : rows) {
			templateMapper.deleteById(row.getId());
		}
		return R.success("已删除 " + rows.size() + " 个模板。列表里不会再出现");
	}

	private void normalize(SysMessageTemplate template) {
		String code = template.getCode() == null ? "" : template.getCode().trim();
		if (code.isEmpty()) {
			throw new ServiceException("请填写模板编码");
		}
		if (!code.matches("^[a-z][a-z0-9_]{0,63}$")) {
			throw new ServiceException("模板编码请用小写字母开头，只能包含小写字母、数字和下划线");
		}
		template.setCode(code);
		String title = template.getTitle() == null ? "" : template.getTitle().trim();
		if (title.isEmpty()) {
			throw new ServiceException("请填写模板标题");
		}
		if (title.length() > 255) {
			throw new ServiceException("模板标题请控制在 255 个字以内");
		}
		assertPlaceholders(title, "标题");
		template.setTitle(title);
		String content = template.getContent() == null ? "" : template.getContent().trim();
		if (content.length() > 20000) {
			throw new ServiceException("模板内容请控制在 20000 个字以内");
		}
		if (!content.isEmpty()) {
			assertPlaceholders(content, "内容");
		}
		template.setContent(content.isEmpty() ? null : content);
	}

	private void assertPlaceholders(String text, String field) {
		String left = PLACEHOLDER.matcher(text).replaceAll("");
		if (left.contains("${")) {
			throw new ServiceException(field + "里的占位请写成 ${name} 这种形式。名称用字母开头，发送时再填上对应的值");
		}
	}
}
