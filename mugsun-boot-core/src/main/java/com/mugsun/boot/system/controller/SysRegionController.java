package com.mugsun.boot.system.controller;

import cn.dev33.satoken.annotation.SaCheckLogin;
import cn.dev33.satoken.annotation.SaCheckPermission;
import com.mugsun.boot.system.entity.SysRegion;
import com.mugsun.boot.system.excel.RegionExcel;
import com.mugsun.boot.system.mapper.SysRegionMapper;
import com.mugsun.core.tool.api.R;
import com.mugsun.core.web.excel.ExcelUtil;
import com.mybatisflex.core.query.QueryWrapper;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 行政区划：按父级编码懒加载树，支持 CRUD 与 Excel 导入导出。
 */
@RestController
@RequestMapping("/system/region")
@SaCheckLogin
public class SysRegionController {

	private final SysRegionMapper regionMapper;

	public SysRegionController(SysRegionMapper regionMapper) {
		this.regionMapper = regionMapper;
	}

	/** 懒加载：取某父级下的直接子节点，标注 leaf 供前端判定是否可展开 */
	@GetMapping("/lazy-tree")
	public R<List<SysRegion>> lazyTree(@RequestParam(defaultValue = "0") String parentCode) {
		List<SysRegion> nodes = regionMapper.selectListByQuery(
			QueryWrapper.create().eq("parent_code", parentCode).orderBy("sort", true));
		List<String> codes = nodes.stream().map(SysRegion::getCode).toList();
		Set<String> hasChildren = new HashSet<>();
		if (!codes.isEmpty()) {
			regionMapper.selectListByQuery(QueryWrapper.create().select("DISTINCT parent_code").in("parent_code", codes))
				.forEach(r -> hasChildren.add(r.getParentCode()));
		}
		nodes.forEach(n -> n.setLeaf(!hasChildren.contains(n.getCode())));
		return R.data(nodes);
	}

	@SaCheckPermission("sys:region:save")
	@PostMapping("/submit")
	public R<Void> submit(@RequestBody SysRegion region) {
		if (region.getId() == null) {
			regionMapper.insertSelective(region);
		} else {
			regionMapper.update(region);
		}
		return R.success("操作成功");
	}

	@SaCheckPermission("sys:region:remove")
	@PostMapping("/remove/{id}")
	public R<Void> remove(@PathVariable Long id) {
		SysRegion region = regionMapper.selectOneById(id);
		if (region == null) {
			return R.success("删除成功");
		}
		long childCount = regionMapper.selectCountByQuery(
			QueryWrapper.create().eq("parent_code", region.getCode()));
		if (childCount > 0) {
			throw new com.mugsun.core.tool.exception.ServiceException("存在下级区划，请先删除子级");
		}
		regionMapper.deleteById(id);
		return R.success("删除成功");
	}

	/** 导出全部区划 */
	@GetMapping("/export")
	public void export(HttpServletResponse response) {
		List<RegionExcel> rows = regionMapper.selectListByQuery(QueryWrapper.create().orderBy("code", true))
			.stream().map(r -> {
				RegionExcel e = new RegionExcel();
				e.setCode(r.getCode());
				e.setParentCode(r.getParentCode());
				e.setName(r.getName());
				e.setLevel(r.getLevel());
				return e;
			}).toList();
		ExcelUtil.export(response, "行政区划.xlsx", "行政区划", rows, RegionExcel.class);
	}

	/** 导入区划：按编码存在则更新，否则新增 */
	@SaCheckPermission("sys:region:import")
	@PostMapping("/import")
	public R<Integer> importData(@RequestParam("file") MultipartFile file) {
		List<RegionExcel> rows = ExcelUtil.read(file, RegionExcel.class);
		int count = 0;
		for (RegionExcel row : rows) {
			if (row.getCode() == null || row.getCode().isBlank()) {
				continue;
			}
			SysRegion exist = regionMapper.selectOneByQuery(QueryWrapper.create().eq("code", row.getCode()));
			SysRegion region = exist != null ? exist : new SysRegion();
			region.setCode(row.getCode());
			region.setParentCode(row.getParentCode() == null ? "0" : row.getParentCode());
			region.setName(row.getName());
			region.setLevel(row.getLevel() == null ? 1 : row.getLevel());
			if (exist != null) {
				regionMapper.update(region);
			} else {
				regionMapper.insertSelective(region);
			}
			count++;
		}
		return R.data(count);
	}
}
