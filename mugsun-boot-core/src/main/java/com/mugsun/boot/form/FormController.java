package com.mugsun.boot.form;

import cn.dev33.satoken.annotation.SaCheckLogin;
import cn.dev33.satoken.stp.StpUtil;
import com.mugsun.boot.common.repeat.RepeatSubmit;
import com.mugsun.boot.form.entity.SysForm;
import com.mugsun.boot.form.entity.SysFormData;
import com.mugsun.boot.form.mapper.SysFormDataMapper;
import com.mugsun.boot.form.mapper.SysFormMapper;
import com.mugsun.boot.system.service.UserService;
import com.mugsun.core.tool.api.R;
import com.mugsun.core.tool.exception.ServiceException;
import com.mybatisflex.core.paginate.Page;
import com.mybatisflex.core.query.QueryWrapper;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.List;

/**
 * 低代码表单：设计（schema 落库）+ 运行时填报（数据落库）。
 */
@RestController
@RequestMapping("/system/form")
@SaCheckLogin
public class FormController {

	private final SysFormMapper formMapper;
	private final SysFormDataMapper formDataMapper;
	private final UserService userService;

	public FormController(SysFormMapper formMapper, SysFormDataMapper formDataMapper, UserService userService) {
		this.formMapper = formMapper;
		this.formDataMapper = formDataMapper;
		this.userService = userService;
	}

	@GetMapping("/page")
	@cn.dev33.satoken.annotation.SaCheckPermission("sys:form:list")
	public R<Page<SysForm>> page(@RequestParam(defaultValue = "1") long pageNum,
								 @RequestParam(defaultValue = "10") long pageSize,
								 @RequestParam(required = false) String name,
								 @RequestParam(required = false) Integer status) {
		if (pageNum < 1 || pageSize < 1 || pageSize > 100) {
			throw new ServiceException("页码从 1 开始，每页最多 100 条");
		}
		if (status != null && status != 0 && status != 1) {
			throw new ServiceException("状态请选择启用或停用");
		}
		QueryWrapper query = QueryWrapper.create().orderBy("id", false);
		if (name != null && !name.isBlank()) {
			query.like("name", name.trim());
		}
		if (status != null) {
			query.eq("status", status);
		}
		return R.data(formMapper.paginate(pageNum, pageSize, query));
	}

	@GetMapping("/detail")
	@cn.dev33.satoken.annotation.SaCheckPermission("sys:form:list")
	public R<SysForm> detail(@RequestParam Long id) {
		SysForm form = formMapper.selectOneById(id);
		if (form == null) {
			throw new ServiceException("这个表单不存在。请刷新列表后再打开");
		}
		return R.data(form);
	}

	/** 按 formKey 取表单（填报页渲染用） */
	@GetMapping("/by-key/{formKey}")
	public R<SysForm> byKey(@PathVariable String formKey) {
		if (formKey == null || formKey.isBlank()) {
			throw new ServiceException("请填写表单标识后再打开");
		}
		SysForm form = formMapper.selectOneByQuery(QueryWrapper.create().eq("form_key", formKey));
		if (form == null) {
			throw new ServiceException("这个表单不存在。请确认标识后再填写");
		}
		return R.data(form);
	}

	/** 保存表单设计（schema + option） */
	@PostMapping("/submit")
	@RepeatSubmit
	public R<Void> submit(@RequestBody SysForm param) {
		if (param == null) {
			throw new ServiceException("请填写表单名称。用来在列表里区分");
		}
		String name = param.getName() == null ? "" : param.getName().trim();
		if (name.isEmpty()) {
			throw new ServiceException("请填写表单名称。用来在列表里区分");
		}
		if (name.length() > 64) {
			throw new ServiceException("表单名称请控制在 64 个字以内");
		}
		String formKey = param.getFormKey() == null ? "" : param.getFormKey().trim();
		if (!formKey.matches("^[A-Za-z][A-Za-z0-9_]{0,63}$")) {
			throw new ServiceException("表单标识请用字母开头，只能包含字母、数字或下划线");
		}
		if (param.getStatus() != null && param.getStatus() != 0 && param.getStatus() != 1) {
			throw new ServiceException("状态请选择启用或停用");
		}
		if (param.getRemark() != null && param.getRemark().length() > 255) {
			throw new ServiceException("备注请控制在 255 个字以内");
		}
		if (param.getFormSchema() != null && param.getFormSchema().length() > 100000) {
			throw new ServiceException("表单设计内容过长。请精简字段后再保存");
		}
		param.setName(name);
		param.setFormKey(formKey);
		if (param.getId() == null) {
			SysForm exists = formMapper.selectOneByQuery(QueryWrapper.create().eq("form_key", formKey));
			if (exists != null) {
				throw new ServiceException("标识「" + formKey + "」已经有了。请换一个，或编辑原来那个");
			}
			if (param.getStatus() == null) {
				param.setStatus(1);
			}
			formMapper.insertSelective(param);
			return R.success("已创建。接下来可以设计字段，设计完就能填报");
		}
		SysForm before = formMapper.selectOneById(param.getId());
		if (before == null) {
			throw new ServiceException("这个表单不存在。请刷新列表后再保存");
		}
		if (!formKey.equals(before.getFormKey())) {
			throw new ServiceException("表单标识创建后不能改。请沿用原来的标识");
		}
		formMapper.update(param);
		return R.success("已保存。预览和填报会用这份设计");
	}

	@PostMapping("/remove")
	@RepeatSubmit
	public R<Void> remove(@RequestBody List<Long> ids) {
		if (ids == null || ids.isEmpty()) {
			throw new ServiceException("请先选择要删除的表单");
		}
		List<Long> distinct = new ArrayList<>();
		for (Long id : ids) {
			if (id == null) {
				throw new ServiceException("有表单不存在。请刷新列表后再删除");
			}
			if (!distinct.contains(id)) {
				distinct.add(id);
			}
		}
		List<SysForm> forms = formMapper.selectListByIds(distinct);
		if (forms.size() != distinct.size()) {
			throw new ServiceException("有表单不存在，或已经被删除。请刷新列表后再试");
		}
		for (SysForm form : forms) {
			if (form.getFormKey() != null && !form.getFormKey().isBlank()) {
				formDataMapper.deleteByQuery(QueryWrapper.create().eq("form_key", form.getFormKey()));
			}
		}
		formMapper.deleteBatchByIds(distinct);
		return R.success("已删除 " + forms.size() + " 个表单。填报记录也会一起清掉");
	}

	/** 运行时填报：保存填报数据 */
	@PostMapping("/submit-data/{formKey}")
	@RepeatSubmit
	public R<Void> submitData(@PathVariable String formKey, @RequestBody String formData) {
		if (formKey == null || formKey.isBlank()) {
			throw new ServiceException("请先打开要填写的表单");
		}
		SysForm form = formMapper.selectOneByQuery(QueryWrapper.create().eq("form_key", formKey));
		if (form == null) {
			throw new ServiceException("这个表单不存在。请回到列表重新打开");
		}
		if (Integer.valueOf(0).equals(form.getStatus())) {
			throw new ServiceException("这个表单已停用。请让管理员启用后再填");
		}
		if (formData == null || formData.isBlank()) {
			throw new ServiceException("请先填写内容再提交");
		}
		if (formData.length() > 20000) {
			throw new ServiceException("填报内容过长。请精简后再提交");
		}
		SysFormData data = new SysFormData();
		data.setFormKey(formKey);
		data.setFormData(formData);
		data.setSubmitter(StpUtil.getLoginIdAsLong());
		formDataMapper.insertSelective(data);
		return R.success("已提交。可以在填报记录里看到");
	}

	/** 填报记录：按填报人收口（管理侧经 form 管理权限看全量，普通用户仅见本人填报） */
	@GetMapping("/data/{formKey}")
	public R<Page<SysFormData>> data(@PathVariable String formKey,
									 @RequestParam(defaultValue = "1") long pageNum,
									 @RequestParam(defaultValue = "10") long pageSize) {
		if (formKey == null || formKey.isBlank()) {
			throw new ServiceException("请先打开要查看的表单");
		}
		if (pageNum < 1 || pageSize < 1 || pageSize > 100) {
			throw new ServiceException("页码从 1 开始，每页最多 100 条");
		}
		QueryWrapper query = QueryWrapper.create().eq("form_key", formKey).orderBy("id", false);
		if (!StpUtil.hasPermission("sys:form:list")) {
			query.and("submitter = ?", StpUtil.getLoginIdAsLong());
		}
		Page<SysFormData> page = formDataMapper.paginate(pageNum, Math.min(pageSize, 500), query);
		fillSubmitterName(page.getRecords());
		return R.data(page);
	}

	private void fillSubmitterName(List<SysFormData> records) {
		if (records == null || records.isEmpty()) {
			return;
		}
		java.util.Set<Long> ids = new java.util.HashSet<>();
		for (SysFormData row : records) {
			if (row.getSubmitter() != null) {
				ids.add(row.getSubmitter());
			}
		}
		if (ids.isEmpty()) {
			return;
		}
		java.util.Map<Long, String> names = userService.displayNamesByIds(ids);
		for (SysFormData row : records) {
			if (row.getSubmitter() != null) {
				row.setSubmitterName(names.getOrDefault(row.getSubmitter(), String.valueOf(row.getSubmitter())));
			}
		}
	}
}
