package com.mugsun.boot.datasource;

import cn.dev33.satoken.annotation.SaCheckLogin;
import com.mugsun.boot.common.repeat.RepeatSubmit;
import com.mugsun.boot.datasource.entity.BizCustomer;
import com.mugsun.boot.datasource.mapper.BizCustomerMapper;
import com.mugsun.core.tool.api.R;
import com.mugsun.core.tool.exception.ServiceException;
import com.mybatisflex.core.paginate.Page;
import com.mybatisflex.core.query.QueryWrapper;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 演示业务：客户 CRUD。类级 {@link TenantRouted} 声明式按当前租户隔离策略自动路由——
 * 独立库/独立 schema 的租户其数据落对应源，其余落主库 + tenant_id 字段隔离。无需手工 DataSourceKey 包裹。
 */
@RestController
@RequestMapping("/system/customer")
@SaCheckLogin
@TenantRouted
public class BizCustomerController {

	private final BizCustomerMapper customerMapper;

	public BizCustomerController(BizCustomerMapper customerMapper) {
		this.customerMapper = customerMapper;
	}

	@GetMapping("/page")
	@cn.dev33.satoken.annotation.SaCheckPermission("sys:customer:list")
	public R<Page<BizCustomer>> page(@RequestParam(defaultValue = "1") long pageNum,
									 @RequestParam(defaultValue = "10") long pageSize,
									 @RequestParam(required = false) String name) {
		if (pageNum < 1) {
			throw new ServiceException("页码请从 1 开始");
		}
		if (pageSize < 1 || pageSize > 100) {
			throw new ServiceException("每页条数请选择 1 到 100");
		}
		QueryWrapper query = QueryWrapper.create().orderBy("id", false);
		if (name != null && !name.isBlank()) {
			query.like("name", name.trim());
		}
		return R.data(customerMapper.paginate(pageNum, pageSize, query));
	}

	@PostMapping("/submit")
	@cn.dev33.satoken.annotation.SaCheckPermission("sys:customer:save")
	@RepeatSubmit
	public R<Void> submit(@RequestBody BizCustomer param) {
		if (param == null || param.getName() == null || param.getName().isBlank()) {
			throw new ServiceException("请填写客户名称。用来在列表里区分");
		}
		String name = param.getName().trim();
		if (name.length() > 64) {
			throw new ServiceException("客户名称请控制在 64 个字以内");
		}
		String phone = param.getPhone() == null ? "" : param.getPhone().trim();
		if (phone.length() > 32) {
			throw new ServiceException("电话请控制在 32 个字以内");
		}
		if (!phone.isEmpty() && !phone.matches("[0-9+\\-()（）\\s]{3,32}")) {
			throw new ServiceException("电话请只填数字，可以带空格、短横线或加号");
		}
		String remark = param.getRemark() == null ? "" : param.getRemark().trim();
		if (remark.length() > 255) {
			throw new ServiceException("备注请控制在 255 个字以内");
		}
		param.setName(name);
		param.setPhone(phone.isEmpty() ? null : phone);
		param.setRemark(remark.isEmpty() ? null : remark);
		QueryWrapper duplicated = QueryWrapper.create().eq("name", name);
		if (param.getId() != null) {
			duplicated.ne("id", param.getId());
		}
		if (customerMapper.selectCountByQuery(duplicated) > 0) {
			throw new ServiceException("已经有叫「" + name + "」的客户。请换一个名称，或编辑原来那个");
		}
		boolean creating = param.getId() == null;
		if (creating) {
			param.sanitizeForInsert();
			customerMapper.insertSelective(param);
			return R.success("已新增。列表里可以查到这个客户");
		}
		if (customerMapper.selectOneById(param.getId()) == null) {
			throw new ServiceException("这个客户不存在。请刷新列表后再保存");
		}
		param.sanitizeForUpdate();
		customerMapper.update(param);
		return R.success("已保存。列表会显示最新内容");
	}

	@PostMapping("/remove")
	@cn.dev33.satoken.annotation.SaCheckPermission("sys:customer:remove")
	@RepeatSubmit
	public R<Void> remove(@RequestBody List<Long> ids) {
		if (ids == null || ids.isEmpty()) {
			throw new ServiceException("请先选择要删除的客户");
		}
		if (ids.stream().anyMatch(id -> id == null)) {
			throw new ServiceException("有客户不存在。请刷新列表后再删除");
		}
		List<Long> distinct = ids.stream().distinct().toList();
		long found = customerMapper.selectCountByQuery(QueryWrapper.create().in("id", distinct));
		if (found != distinct.size()) {
			throw new ServiceException("有客户不存在，或已经被删除。请刷新列表后再试");
		}
		customerMapper.deleteBatchByIds(distinct);
		return R.success("已删除 " + distinct.size() + " 个客户。列表里不会再出现");
	}
}
