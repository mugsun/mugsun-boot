package com.mugsun.boot.system.controller;

import cn.dev33.satoken.annotation.SaCheckLogin;
import cn.dev33.satoken.annotation.SaCheckPermission;
import cn.dev33.satoken.annotation.SaMode;
import com.mugsun.boot.common.repeat.RepeatSubmit;
import com.mugsun.boot.system.entity.SysDept;
import com.mugsun.boot.system.entity.SysUser;
import com.mugsun.boot.system.mapper.SysDeptMapper;
import com.mugsun.boot.system.mapper.SysUserMapper;
import com.mugsun.core.tool.api.R;
import com.mugsun.core.tool.exception.ServiceException;
import com.mugsun.core.tool.tree.TreeUtil;
import com.mybatisflex.core.query.QueryColumn;
import com.mybatisflex.core.query.QueryWrapper;
import com.mybatisflex.core.update.UpdateChain;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 部门管理
 */
@RestController
@RequestMapping("/system/dept")
@SaCheckLogin
public class SysDeptController {

	private static final Set<String> CATEGORIES = Set.of("company", "dept", "team");

	private final SysDeptMapper deptMapper;
	private final SysUserMapper userMapper;

	public SysDeptController(SysDeptMapper deptMapper, SysUserMapper userMapper) {
		this.deptMapper = deptMapper;
		this.userMapper = userMapper;
	}

	@SaCheckPermission(value = {"sys:dept:save", "sys:dept:remove"}, mode = SaMode.OR)
	@GetMapping("/tree")
	public R<List<SysDept>> tree(@RequestParam(required = false) String deptName,
								 @RequestParam(required = false) String fullName) {
		List<SysDept> all = deptMapper.selectListByQuery(QueryWrapper.create().orderBy("sort", true));
		fillLeaderNames(all);
		String nameKey = deptName == null ? "" : deptName.trim();
		String fullKey = fullName == null ? "" : fullName.trim();
		if (!nameKey.isEmpty() || !fullKey.isEmpty()) {
			Map<Long, SysDept> byId = new HashMap<>();
			for (SysDept dept : all) {
				byId.put(dept.getId(), dept);
			}
			Set<Long> keep = new HashSet<>();
			for (SysDept dept : all) {
				boolean hit = (!nameKey.isEmpty() && contains(dept.getDeptName(), nameKey))
					|| (!fullKey.isEmpty() && contains(dept.getFullName(), fullKey));
				if (!hit) {
					continue;
				}
				SysDept cur = dept;
				while (cur != null && keep.add(cur.getId())) {
					cur = byId.get(cur.getParentId());
				}
			}
			all = all.stream().filter(dept -> keep.contains(dept.getId())).toList();
		}
		return R.data(TreeUtil.build(all, 0L));
	}

	/** 用户、角色表单用的部门树，只含 id、名称和层级，不开放部门管理接口。 */
	@SaCheckPermission(value = {
		"sys:dept:save", "sys:dept:remove", "sys:user:list", "sys:role:list"
	}, mode = SaMode.OR)
	@GetMapping("/options")
	public R<List<SysDept>> options() {
		List<SysDept> all = deptMapper.selectListByQuery(QueryWrapper.create().orderBy("sort", true));
		return R.data(TreeUtil.build(all, 0L));
	}

	/** 部门下拉选项（value/label 契约，供上级部门选择等场景） */
	@SaCheckPermission(value = {
		"sys:dept:save", "sys:dept:remove",
		"sys:notice:manage", "sys:flow:design", "sys:flow:design-graph", "sys:flow:definition"
	}, mode = SaMode.OR)
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

	/** 选部门主管。只返回在职人员的编号和姓名。 */
	@SaCheckPermission("sys:dept:save")
	@GetMapping("/leaders")
	public R<List<Map<String, Object>>> leaders(@RequestParam(required = false) String keyword) {
		QueryWrapper query = QueryWrapper.create().eq("status", 1).orderBy("id", false).limit(200);
		if (keyword != null && !keyword.isBlank()) {
			String kw = keyword.trim();
			query.and(new QueryColumn("username").like(kw).or(new QueryColumn("nickname").like(kw)));
		}
		List<Map<String, Object>> options = userMapper.selectListByQuery(query).stream()
			.map(user -> {
				Map<String, Object> option = new HashMap<>();
				option.put("value", user.getId());
				String label = user.getNickname() != null && !user.getNickname().isBlank()
					? user.getNickname()
					: user.getUsername();
				option.put("label", label);
				return option;
			})
			.toList();
		return R.data(options);
	}

	@SaCheckPermission(value = {"sys:dept:save", "sys:dept:remove"}, mode = SaMode.OR)
	@GetMapping("/detail")
	public R<SysDept> detail(@RequestParam Long id) {
		SysDept dept = deptMapper.selectOneById(id);
		if (dept == null) {
			throw new ServiceException("这个部门不存在，或属于其他租户。请刷新列表后再看");
		}
		fillLeaderNames(List.of(dept));
		return R.data(dept);
	}

	@RepeatSubmit(message = "请勿重复保存。请到列表里确认这个部门是否已经提交")
	@SaCheckPermission("sys:dept:save")
	@PostMapping("/submit")
	public R<Void> submit(@RequestBody SysDept dept) {
		if (dept == null || dept.getDeptName() == null || dept.getDeptName().isBlank()) {
			throw new ServiceException("请填写部门名称");
		}
		if (dept.getDeptName().trim().length() > 64) {
			throw new ServiceException("部门名称请控制在 64 字以内");
		}
		dept.setDeptName(dept.getDeptName().trim());
		if (dept.getFullName() == null || dept.getFullName().isBlank()) {
			throw new ServiceException("请填写部门全称，方便在列表里和简称区分");
		}
		if (dept.getFullName().trim().length() > 128) {
			throw new ServiceException("部门全称请控制在 128 字以内");
		}
		dept.setFullName(dept.getFullName().trim());
		if (dept.getCategory() == null || !CATEGORIES.contains(dept.getCategory())) {
			throw new ServiceException("请选择公司、部门或小组");
		}
		if (dept.getParentId() == null) {
			dept.setParentId(0L);
		}
		if (dept.getSort() == null) {
			dept.setSort(0);
		}
		if (dept.getSort() < 0 || dept.getSort() > 9999) {
			throw new ServiceException("排序请填写 0 到 9999 之间的数字");
		}
		assertParent(dept);
		assertNameFree(dept);
		assertLeader(dept.getLeaderId());
		if (dept.getId() == null) {
			dept.sanitizeForInsert();
			dept.setTenantId(null);
			deptMapper.insert(dept);
		} else {
			SysDept exist = deptMapper.selectOneById(dept.getId());
			if (exist == null) {
				throw new ServiceException("这个部门不存在，或属于其他租户。请刷新列表后再编辑");
			}
			dept.sanitizeForUpdate();
			dept.setTenantId(null);
			deptMapper.update(dept);
			UpdateChain.of(SysDept.class)
				.set("leader_id", dept.getLeaderId())
				.where("id = ?", dept.getId())
				.update();
		}
		return R.success("已保存。可以在列表里查看这个部门");
	}

	@RepeatSubmit(message = "请勿重复删除。请刷新列表确认这些部门是否还在")
	@SaCheckPermission("sys:dept:remove")
	@PostMapping("/remove")
	@Transactional(rollbackFor = Exception.class)
	public R<Void> remove(@RequestBody List<Long> ids) {
		if (ids == null || ids.isEmpty()) {
			throw new ServiceException("请先勾选要删除的部门");
		}
		List<SysDept> targets = new ArrayList<>();
		for (Long id : ids) {
			if (id == null) {
				continue;
			}
			SysDept exist = deptMapper.selectOneById(id);
			if (exist != null) {
				targets.add(exist);
			}
		}
		if (targets.isEmpty()) {
			throw new ServiceException("这些部门不存在，或已被删除。请刷新列表后再试");
		}
		for (SysDept exist : targets) {
			if (deptMapper.selectCountByQuery(QueryWrapper.create().eq("parent_id", exist.getId())) > 0) {
				throw new ServiceException("「" + exist.getDeptName() + "」下面还有下级部门。请先删除下级，再删除这一级");
			}
			if (userMapper.selectCountByQuery(QueryWrapper.create().eq("dept_id", exist.getId())) > 0) {
				throw new ServiceException("还有人在「" + exist.getDeptName() + "」。请先到用户管理里换掉他们的部门，再删除");
			}
		}
		for (SysDept exist : targets) {
			deptMapper.deleteById(exist.getId());
		}
		return R.success("已删除。列表里不会再出现这些部门");
	}

	private void assertParent(SysDept dept) {
		Long parentId = dept.getParentId();
		if (parentId == null || parentId == 0L) {
			dept.setParentId(0L);
			return;
		}
		if (dept.getId() != null && parentId.equals(dept.getId())) {
			throw new ServiceException("上级部门不能选自己。请改选上一级，或选顶级部门");
		}
		Long cursor = parentId;
		int guard = 0;
		while (cursor != null && cursor != 0L) {
			if (dept.getId() != null && cursor.equals(dept.getId())) {
				throw new ServiceException("上级部门不能选自己或自己的下级。请改选上一级，或选顶级部门");
			}
			SysDept parent = deptMapper.selectOneById(cursor);
			if (parent == null) {
				throw new ServiceException("上级部门不存在，或属于其他租户。请刷新列表后重新选择");
			}
			cursor = parent.getParentId();
			if (++guard > 64) {
				throw new ServiceException("部门层级太深。请先整理上级，再保存");
			}
		}
	}

	private void assertNameFree(SysDept dept) {
		QueryWrapper query = QueryWrapper.create()
			.eq("parent_id", dept.getParentId())
			.eq("dept_name", dept.getDeptName());
		if (dept.getId() != null) {
			query.ne("id", dept.getId());
		}
		if (deptMapper.selectCountByQuery(query) > 0) {
			throw new ServiceException("这个上级下面已经有同名部门。请换一个名称，或编辑原来那个");
		}
	}

	private void assertLeader(Long leaderId) {
		if (leaderId == null) {
			return;
		}
		SysUser leader = userMapper.selectOneById(leaderId);
		if (leader == null) {
			throw new ServiceException("这位主管不存在，或属于其他租户。请重新选择");
		}
	}

	private void fillLeaderNames(List<SysDept> depts) {
		Set<Long> leaderIds = new HashSet<>();
		for (SysDept dept : depts) {
			if (dept.getLeaderId() != null) {
				leaderIds.add(dept.getLeaderId());
			}
		}
		if (leaderIds.isEmpty()) {
			return;
		}
		Map<Long, String> names = new HashMap<>();
		userMapper.selectListByQuery(QueryWrapper.create().in("id", leaderIds))
			.forEach(user -> {
				String label = user.getNickname() != null && !user.getNickname().isBlank()
					? user.getNickname()
					: user.getUsername();
				names.put(user.getId(), label);
			});
		for (SysDept dept : depts) {
			if (dept.getLeaderId() != null) {
				dept.setLeaderName(names.get(dept.getLeaderId()));
			}
		}
	}

	private static boolean contains(String value, String keyword) {
		return value != null && value.contains(keyword);
	}
}
