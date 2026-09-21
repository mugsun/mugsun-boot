package com.mugsun.boot.system.controller;

import cn.dev33.satoken.annotation.SaCheckLogin;
import cn.dev33.satoken.annotation.SaCheckPermission;
import com.mugsun.boot.system.entity.SysDict;
import com.mugsun.boot.system.mapper.SysDictMapper;
import com.mugsun.boot.system.service.DictService;
import com.mugsun.core.tool.api.R;
import com.mugsun.core.tool.tree.TreeUtil;
import com.mybatisflex.core.query.QueryWrapper;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 系统字典管理
 */
@RestController
@RequestMapping("/system/dict")
@SaCheckLogin
public class SysDictController {

	private final SysDictMapper dictMapper;
	private final DictService dictService;

	public SysDictController(SysDictMapper dictMapper, DictService dictService) {
		this.dictMapper = dictMapper;
		this.dictService = dictService;
	}

	@GetMapping("/tree")
	public R<List<SysDict>> tree(@RequestParam(required = false) String code,
								 @RequestParam(required = false) String dictValue) {
		QueryWrapper query = QueryWrapper.create().orderBy("sort", true);
		// 查询条件（值走参数化绑定，LIKE 前后模糊）
		boolean filtering = false;
		if (code != null && !code.isBlank()) {
			query.like("code", code.trim());
			filtering = true;
		}
		if (dictValue != null && !dictValue.isBlank()) {
			query.like("dict_value", dictValue.trim());
			filtering = true;
		}
		List<SysDict> all = dictMapper.selectListByQuery(query);
		// 过滤时回填亲属节点：命中集丢祖先/子孙会让树断裂（孤儿节点构树不可见）
		if (filtering && !all.isEmpty()) {
			all = withRelatives(all);
		}
		return R.data(TreeUtil.build(all, 0L));
	}

	/**
	 * 过滤命中集的亲属回填：向上补祖先（防命中字典项丢父类型）、向下补整棵子树（命中类型须带出全部字典项）。
	 * 逐级 in 查询（值参数化，深度=树高，字典树常规 2 层），最终按 id 集按原排序口径取回，保序与未过滤一致。
	 */
	private List<SysDict> withRelatives(List<SysDict> matched) {
		java.util.Set<Long> ids = new java.util.HashSet<>();
		matched.forEach(d -> ids.add(d.getId()));
		// 向上：逐层补祖先直至 parent_id=0（contains 防环，每节点只入一次）
		java.util.Set<Long> up = new java.util.HashSet<>();
		for (SysDict d : matched) {
			if (d.getParentId() != null && d.getParentId() != 0L && !ids.contains(d.getParentId())) {
				up.add(d.getParentId());
			}
		}
		while (!up.isEmpty()) {
			List<SysDict> parents = dictMapper.selectListByQuery(QueryWrapper.create().in("id", up));
			up.clear();
			for (SysDict p : parents) {
				if (ids.add(p.getId()) && p.getParentId() != null && p.getParentId() != 0L
					&& !ids.contains(p.getParentId())) {
					up.add(p.getParentId());
				}
			}
		}
		// 向下：逐层补子孙（同上防环）
		java.util.Set<Long> down = new java.util.HashSet<>(ids);
		while (!down.isEmpty()) {
			List<SysDict> children = dictMapper.selectListByQuery(QueryWrapper.create().in("parent_id", down));
			down.clear();
			for (SysDict c : children) {
				if (ids.add(c.getId())) {
					down.add(c.getId());
				}
			}
		}
		return dictMapper.selectListByQuery(QueryWrapper.create().in("id", ids).orderBy("sort", true));
	}

	/** 按编码查字典项（走缓存） */
	@GetMapping("/dictionary")
	public R<List<SysDict>> dictionary(@RequestParam String code) {
		return R.data(dictService.listItems(code));
	}

	/** 批量按编码查字典项（一次拉多码，各码走缓存，供前端字典运行时并发去重） */
	@PostMapping("/batch")
	public R<Map<String, List<SysDict>>> batch(@RequestBody List<String> codes) {
		Map<String, List<SysDict>> result = new LinkedHashMap<>();
		if (codes != null) {
			for (String code : codes) {
				if (code != null && !result.containsKey(code)) {
					result.put(code, dictService.listItems(code));
				}
			}
		}
		return R.data(result);
	}

	@GetMapping("/detail")
	public R<SysDict> detail(@RequestParam Long id) {
		return R.data(dictMapper.selectOneById(id));
	}

	@SaCheckPermission("sys:dict:save")
	@PostMapping("/submit")
	public R<Void> submit(@RequestBody SysDict dict) {
		// 字典类型无键值：dictKey 兜底空串，避免 NOT NULL 约束报错
		if (dict.getDictKey() == null) {
			dict.setDictKey("");
		}
		if (dict.getParentId() != null && dict.getParentId().equals(dict.getId())) {
			throw new com.mugsun.core.tool.exception.ServiceException("上级字典不能是自身");
		}
		if (dict.getId() == null) {
			dict.sanitizeForInsert();
			dictMapper.insertSelective(dict);
		} else {
			dict.sanitizeForUpdate();
			dictMapper.update(dict);
		}
		dictService.evict(dict.getCode());
		return R.success("操作成功");
	}

	@SaCheckPermission("sys:dict:remove")
	@PostMapping("/remove")
	public R<Void> remove(@RequestBody List<Long> ids) {
		for (Long id : ids) {
			if (dictMapper.selectCountByQuery(com.mybatisflex.core.query.QueryWrapper.create().eq("parent_id", id)) > 0) {
				throw new com.mugsun.core.tool.exception.ServiceException("存在子级字典，请先删除子级");
			}
		}
		ids.forEach(id -> {
			SysDict dict = dictMapper.selectOneById(id);
			dictMapper.deleteById(id);
			if (dict != null) {
				dictService.evict(dict.getCode());
			}
		});
		return R.success("删除成功");
	}
}
