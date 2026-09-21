package com.mugsun.boot.tenant;

import cn.dev33.satoken.annotation.SaCheckLogin;
import com.mugsun.boot.tenant.entity.SysTenantPackage;
import com.mugsun.boot.tenant.mapper.SysTenantPackageMapper;
import com.mugsun.core.tool.api.R;
import com.mugsun.core.tool.exception.ServiceException;
import com.mybatisflex.core.paginate.Page;
import com.mybatisflex.core.query.QueryWrapper;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 租户套餐管理（平台级，超管操作）：CRUD + 下拉列表。
 */
@RestController
@RequestMapping("/system/tenant-package")
@SaCheckLogin
public class TenantPackageController {

	private final SysTenantPackageMapper packageMapper;
	private final TenantValidator tenantValidator;

	public TenantPackageController(SysTenantPackageMapper packageMapper, TenantValidator tenantValidator) {
		this.packageMapper = packageMapper;
		this.tenantValidator = tenantValidator;
	}

	@GetMapping("/page")
	public R<Page<SysTenantPackage>> page(@RequestParam(defaultValue = "1") long pageNum,
										  @RequestParam(defaultValue = "10") long pageSize,
										  @RequestParam(required = false) String name,
										  @RequestParam(required = false) Integer status) {
		QueryWrapper query = QueryWrapper.create().orderBy("id", false);
		// 查询条件（值走参数化绑定，名称 LIKE 前后模糊，状态精确匹配）
		if (name != null && !name.isBlank()) {
			query.like("name", name.trim());
		}
		if (status != null) {
			query.eq("status", status);
		}
		return R.data(packageMapper.paginate(pageNum, pageSize, query));
	}

	/** 下拉列表：仅启用套餐 */
	@GetMapping("/list")
	public R<List<SysTenantPackage>> list() {
		return R.data(packageMapper.selectListByQuery(
			QueryWrapper.create().eq("status", 1).orderBy("id", false)));
	}

	@GetMapping("/detail")
	public R<SysTenantPackage> detail(@RequestParam Long id) {
		return R.data(packageMapper.selectOneById(id));
	}

	@PostMapping("/submit")
	public R<Void> submit(@RequestBody SysTenantPackage param) {
		if (param.getName() == null || param.getName().isBlank()) {
			throw new ServiceException("套餐名称不能为空");
		}
		if (param.getId() == null) {
			if (param.getStatus() == null) {
				param.setStatus(1);
			}
			packageMapper.insertSelective(param);
		} else {
			packageMapper.update(param);
			tenantValidator.evictPackage(param.getId());
		}
		return R.success("保存成功");
	}

	@PostMapping("/remove")
	public R<Void> remove(@RequestBody List<Long> ids) {
		packageMapper.deleteBatchByIds(ids);
		ids.forEach(tenantValidator::evictPackage);
		return R.success("删除成功");
	}
}
