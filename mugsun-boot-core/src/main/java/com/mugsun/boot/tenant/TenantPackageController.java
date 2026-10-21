package com.mugsun.boot.tenant;

import cn.dev33.satoken.annotation.SaCheckLogin;
import com.mugsun.boot.common.repeat.RepeatSubmit;
import com.mugsun.boot.system.entity.SysTenant;
import com.mugsun.boot.system.service.TenantService;
import com.mugsun.boot.tenant.entity.SysTenantPackage;
import com.mugsun.boot.tenant.mapper.SysTenantPackageMapper;
import com.mugsun.core.tool.api.R;
import com.mugsun.core.tool.exception.ServiceException;
import com.mybatisflex.core.paginate.Page;
import com.mybatisflex.core.query.QueryWrapper;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.regex.Pattern;

/**
 * 租户套餐管理（平台级，超管操作）：CRUD + 下拉列表。
 */
@RestController
@RequestMapping("/system/tenant-package")
@SaCheckLogin
public class TenantPackageController {

	private static final Pattern MENU_KEY = Pattern.compile("^[A-Za-z][A-Za-z0-9_]{0,63}$");

	private final SysTenantPackageMapper packageMapper;
	private final TenantService tenantService;
	private final TenantValidator tenantValidator;

	public TenantPackageController(SysTenantPackageMapper packageMapper, TenantService tenantService,
								   TenantValidator tenantValidator) {
		this.packageMapper = packageMapper;
		this.tenantService = tenantService;
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
		SysTenantPackage pkg = packageMapper.selectOneById(id);
		if (pkg == null) {
			throw new ServiceException("这个套餐不存在。请刷新列表后再看");
		}
		return R.data(pkg);
	}

	@RepeatSubmit
	@PostMapping("/submit")
	@cn.dev33.satoken.annotation.SaCheckPermission("sys:tenant-package:save")
	public R<Void> submit(@RequestBody SysTenantPackage param) {
		com.mugsun.boot.tenant.TenantContext.requirePlatformSuperAdmin();
		if (param == null) {
			throw new ServiceException("请填写套餐名称后再保存");
		}
		normalize(param);
		if (param.getId() == null) {
			packageMapper.insertSelective(param);
		} else {
			if (packageMapper.selectOneById(param.getId()) == null) {
				throw new ServiceException("这个套餐不存在。请刷新列表后再编辑");
			}
			SysTenantPackage upd = com.mybatisflex.core.util.UpdateEntity.of(SysTenantPackage.class, param.getId());
			upd.setName(param.getName());
			upd.setMenuKeys(param.getMenuKeys());
			upd.setStatus(param.getStatus());
			upd.setRemark(param.getRemark());
			packageMapper.update(upd);
			tenantValidator.evictPackage(param.getId());
		}
		return R.success("已保存。列表里可以查看这个套餐包含多少功能");
	}

	@RepeatSubmit
	@PostMapping("/remove")
	@cn.dev33.satoken.annotation.SaCheckPermission("sys:tenant-package:remove")
	public R<Void> remove(@RequestBody List<Long> ids) {
		com.mugsun.boot.tenant.TenantContext.requirePlatformSuperAdmin();
		if (ids == null || ids.isEmpty()) {
			throw new ServiceException("请先选择要删除的套餐");
		}
		LinkedHashSet<Long> distinct = new LinkedHashSet<>();
		for (Long id : ids) {
			if (id == null) {
				throw new ServiceException("有套餐不存在。请刷新列表后再删除");
			}
			distinct.add(id);
		}
		List<Long> idList = new ArrayList<>(distinct);
		List<SysTenantPackage> packages = packageMapper.selectListByIds(idList);
		if (packages.size() != idList.size()) {
			throw new ServiceException("有套餐不存在，或已经被删除。请刷新列表后再试");
		}
		List<SysTenant> bound = tenantService.listByPackageIds(idList);
		if (!bound.isEmpty()) {
			SysTenant tenant = bound.get(0);
			String packageName = packages.stream()
				.filter(pkg -> pkg.getId().equals(tenant.getPackageId()))
				.map(SysTenantPackage::getName)
				.findFirst()
				.orElse("该套餐");
			String tenantName = tenant.getTenantName() == null || tenant.getTenantName().isBlank()
				? tenant.getTenantCode() : tenant.getTenantName();
			throw new ServiceException("还有租户在用「" + packageName + "」，例如「" + tenantName
				+ "」。请先到租户管理里换掉或清空他们的套餐，再删除");
		}
		packageMapper.deleteBatchByIds(idList);
		idList.forEach(tenantValidator::evictPackage);
		return R.success("已删除 " + packages.size() + " 个套餐。列表里不会再出现");
	}

	private void normalize(SysTenantPackage param) {
		String name = param.getName() == null ? "" : param.getName().trim();
		if (name.isEmpty()) {
			throw new ServiceException("请填写套餐名称");
		}
		if (name.length() > 64) {
			throw new ServiceException("套餐名称请控制在 64 个字以内");
		}
		param.setName(name);
		QueryWrapper duplicate = QueryWrapper.create().eq("name", name);
		if (param.getId() != null) {
			duplicate.ne("id", param.getId());
		}
		if (packageMapper.selectCountByQuery(duplicate) > 0) {
			throw new ServiceException("已经有叫「" + name + "」的套餐。请换一个名称，或编辑原来那个");
		}
		if (param.getStatus() == null) {
			param.setStatus(1);
		} else if (param.getStatus() != 0 && param.getStatus() != 1) {
			throw new ServiceException("状态只能选择启用或停用");
		}
		String remark = param.getRemark() == null ? "" : param.getRemark().trim();
		if (remark.length() > 255) {
			throw new ServiceException("备注请控制在 255 个字以内");
		}
		param.setRemark(remark.isEmpty() ? null : remark);
		String rawKeys = param.getMenuKeys() == null ? "" : param.getMenuKeys();
		LinkedHashSet<String> keys = new LinkedHashSet<>();
		for (String part : rawKeys.split(",")) {
			String key = part.trim();
			if (key.isEmpty()) {
				continue;
			}
			if (!MENU_KEY.matcher(key).matches()) {
				throw new ServiceException("功能菜单标识不合法。请在弹窗里重新勾选，不要手写");
			}
			keys.add(key);
		}
		String joined = String.join(",", keys);
		if (joined.length() > 4000) {
			throw new ServiceException("勾选的功能菜单过多。请减少一些后再保存");
		}
		param.setMenuKeys(joined.isEmpty() ? null : joined);
	}
}
