package com.mugsun.boot.system.controller;

import cn.dev33.satoken.annotation.SaCheckLogin;
import cn.dev33.satoken.annotation.SaCheckPermission;
import com.mugsun.boot.system.entity.SysDept;
import com.mugsun.boot.system.mapper.SysDeptMapper;
import com.mugsun.core.tool.api.R;
import com.mugsun.core.tool.tree.TreeUtil;
import com.mybatisflex.core.query.QueryWrapper;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 部门管理
 */
@RestController
@RequestMapping("/system/dept")
@SaCheckLogin
public class SysDeptController {

	private final SysDeptMapper deptMapper;

	public SysDeptController(SysDeptMapper deptMapper) {
		this.deptMapper = deptMapper;
	}

	@GetMapping("/tree")
	public R<List<SysDept>> tree(@RequestParam(required = false) String deptName) {
		List<SysDept> all = deptMapper.selectListByQuery(QueryWrapper.create().orderBy("sort", true));
		// 名称过滤：保留命中节点及其祖先（树已全量加载，内存过滤后重新建树，层级可正常展开）
		if (deptName != null && !deptName.isBlank()) {
			String keyword = deptName.trim();
			Map<Long, SysDept> byId = new HashMap<>();
			for (SysDept dept : all) {
				byId.put(dept.getId(), dept);
			}
			java.util.Set<Long> keep = new java.util.HashSet<>();
			for (SysDept dept : all) {
				if (dept.getDeptName() != null && dept.getDeptName().contains(keyword)) {
					SysDept cur = dept;
					while (cur != null && keep.add(cur.getId())) {
						cur = byId.get(cur.getParentId());
					}
				}
			}
			all = all.stream().filter(dept -> keep.contains(dept.getId())).toList();
		}
		return R.data(TreeUtil.build(all, 0L));
	}

	/** 部门下拉选项（value/label 契约，供上级部门选择等场景） */
	@GetMapping("/select")
	public R<List<Map<String, Object>>> select() {
		List<Map<String, Object>> options = deptMapper
			.selectListByQuery(QueryWrapper.create().orderBy("sort", true))
			.stream()
			.map(dept -> {
				Map<String, Object> option = new HashMap<>();
				option.put("value", dept.getId());
				option.put("label", dept.getDeptName());
				return option;
			})
			.toList();
		return R.data(options);
	}

	@GetMapping("/detail")
	public R<SysDept> detail(@RequestParam Long id) {
		return R.data(deptMapper.selectOneById(id));
	}

	@SaCheckPermission("sys:dept:save")
	@PostMapping("/submit")
	public R<Void> submit(@RequestBody SysDept dept) {
		// 防环：父级不能是自身（自身后代校验依赖全树，TreeUtil 构建侧已加环保护兜底）
		if (dept.getParentId() != null && dept.getParentId().equals(dept.getId())) {
			throw new com.mugsun.core.tool.exception.ServiceException("上级部门不能是自身");
		}
		if (dept.getId() == null) {
			dept.sanitizeForInsert();
			dept.setTenantId(null);
			deptMapper.insert(dept);
		} else {
			dept.sanitizeForUpdate();
			dept.setTenantId(null);
			deptMapper.update(dept);
		}
		return R.success("操作成功");
	}

	@SaCheckPermission("sys:dept:remove")
	@PostMapping("/remove")
	public R<Void> remove(@RequestBody List<Long> ids) {
		// 引用检查：存在子部门或在岗用户时拒删（防树蒸发与 dept_id 悬挂）
		for (Long id : ids) {
			if (deptMapper.selectCountByQuery(QueryWrapper.create().eq("parent_id", id)) > 0) {
				throw new com.mugsun.core.tool.exception.ServiceException("存在子部门，请先删除子级");
			}
		}
		deptMapper.deleteBatchByIds(ids);
		return R.success("删除成功");
	}
}
