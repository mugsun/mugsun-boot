package com.mugsun.boot.form;

import cn.dev33.satoken.annotation.SaCheckLogin;
import cn.dev33.satoken.stp.StpUtil;
import com.mugsun.boot.form.entity.SysForm;
import com.mugsun.boot.form.entity.SysFormData;
import com.mugsun.boot.form.mapper.SysFormDataMapper;
import com.mugsun.boot.form.mapper.SysFormMapper;
import com.mugsun.core.tool.api.R;
import com.mugsun.core.tool.exception.ServiceException;
import com.mybatisflex.core.paginate.Page;
import com.mybatisflex.core.query.QueryWrapper;
import org.springframework.web.bind.annotation.*;

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

	public FormController(SysFormMapper formMapper, SysFormDataMapper formDataMapper) {
		this.formMapper = formMapper;
		this.formDataMapper = formDataMapper;
	}

	@GetMapping("/page")
	@cn.dev33.satoken.annotation.SaCheckPermission("sys:form:list")
	public R<Page<SysForm>> page(@RequestParam(defaultValue = "1") long pageNum,
								 @RequestParam(defaultValue = "10") long pageSize) {
		return R.data(formMapper.paginate(pageNum, Math.min(pageSize, 500), QueryWrapper.create().orderBy("id", false)));
	}

	@GetMapping("/detail")
	@cn.dev33.satoken.annotation.SaCheckPermission("sys:form:list")
	public R<SysForm> detail(@RequestParam Long id) {
		return R.data(formMapper.selectOneById(id));
	}

	/** 按 formKey 取表单（填报页渲染用） */
	@GetMapping("/by-key/{formKey}")
	public R<SysForm> byKey(@PathVariable String formKey) {
		return R.data(formMapper.selectOneByQuery(QueryWrapper.create().eq("form_key", formKey)));
	}

	/** 保存表单设计（schema + option） */
	@PostMapping("/submit")
	public R<Void> submit(@RequestBody SysForm param) {
		if (param.getName() == null || param.getName().isBlank()) {
			throw new ServiceException("表单名称不能为空");
		}
		if (param.getFormKey() == null || param.getFormKey().isBlank()) {
			throw new ServiceException("表单标识不能为空");
		}
		if (param.getId() == null) {
			SysForm exists = formMapper.selectOneByQuery(QueryWrapper.create().eq("form_key", param.getFormKey()));
			if (exists != null) {
				throw new ServiceException("表单标识已存在");
			}
			if (param.getStatus() == null) {
				param.setStatus(1);
			}
			formMapper.insertSelective(param);
		} else {
			formMapper.update(param);
		}
		return R.success("保存成功");
	}

	@PostMapping("/remove")
	public R<Void> remove(@RequestBody List<Long> ids) {
		formMapper.deleteBatchByIds(ids);
		return R.success("删除成功");
	}

	/** 运行时填报：保存填报数据 */
	@PostMapping("/submit-data/{formKey}")
	public R<Void> submitData(@PathVariable String formKey, @RequestBody String formData) {
		SysForm form = formMapper.selectOneByQuery(QueryWrapper.create().eq("form_key", formKey));
		if (form == null) {
			throw new ServiceException("表单不存在");
		}
		SysFormData data = new SysFormData();
		data.setFormKey(formKey);
		data.setFormData(formData);
		data.setSubmitter(StpUtil.getLoginIdAsLong());
		formDataMapper.insertSelective(data);
		return R.success("提交成功");
	}

	/** 填报记录：按填报人收口（管理侧经 form 管理权限看全量，普通用户仅见本人填报） */
	@GetMapping("/data/{formKey}")
	public R<Page<SysFormData>> data(@PathVariable String formKey,
									 @RequestParam(defaultValue = "1") long pageNum,
									 @RequestParam(defaultValue = "10") long pageSize) {
		QueryWrapper query = QueryWrapper.create().eq("form_key", formKey).orderBy("id", false);
		if (!StpUtil.hasPermission("sys:form:list")) {
			query.and("submitter = ?", StpUtil.getLoginIdAsLong());
		}
		return R.data(formDataMapper.paginate(pageNum, Math.min(pageSize, 500), query));
	}
}
