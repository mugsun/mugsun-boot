package com.mugsun.boot.datasource;

import cn.dev33.satoken.annotation.SaCheckLogin;
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
									 @RequestParam(defaultValue = "10") long pageSize) {
		return R.data(customerMapper.paginate(pageNum, Math.min(pageSize, 500), QueryWrapper.create().orderBy("id", false)));
	}

	@PostMapping("/submit")
	@cn.dev33.satoken.annotation.SaCheckPermission("sys:customer:save")
	public R<Void> submit(@RequestBody BizCustomer param) {
		if (param.getName() == null || param.getName().isBlank()) {
			throw new ServiceException("客户名称不能为空");
		}
		if (param.getId() == null) {
			param.sanitizeForInsert();
			customerMapper.insertSelective(param);
		} else {
			param.sanitizeForUpdate();
			customerMapper.update(param);
		}
		return R.success("保存成功");
	}

	@PostMapping("/remove")
	@cn.dev33.satoken.annotation.SaCheckPermission("sys:customer:remove")
	public R<Void> remove(@RequestBody List<Long> ids) {
		customerMapper.deleteBatchByIds(ids);
		return R.success("删除成功");
	}
}
