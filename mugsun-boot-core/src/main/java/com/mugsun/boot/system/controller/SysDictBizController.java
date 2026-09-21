package com.mugsun.boot.system.controller;

import cn.dev33.satoken.annotation.SaCheckLogin;
import cn.dev33.satoken.annotation.SaCheckPermission;
import com.mugsun.boot.system.entity.SysDictBiz;
import com.mugsun.boot.system.mapper.SysDictBizMapper;
import com.mugsun.core.tool.api.R;
import com.mugsun.core.tool.tree.TreeUtil;
import com.mybatisflex.core.query.QueryWrapper;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 业务字典管理（租户隔离于 G8 多租户阶段接入）
 */
@RestController
@RequestMapping("/system/dict-biz")
@SaCheckLogin
public class SysDictBizController {

	private final SysDictBizMapper dictBizMapper;

	public SysDictBizController(SysDictBizMapper dictBizMapper) {
		this.dictBizMapper = dictBizMapper;
	}

	@GetMapping("/tree")
	public R<List<SysDictBiz>> tree(@RequestParam(required = false) String code,
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
		List<SysDictBiz> all = dictBizMapper.selectListByQuery(query);
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
	private List<SysDictBiz> withRelatives(List<SysDictBiz> matched) {
		java.util.Set<Long> ids = new java.util.HashSet<>();
		matched.forEach(d -> ids.add(d.getId()));
		// 向上：逐层补祖先直至 parent_id=0（contains 防环，每节点只入一次）
		java.util.Set<Long> up = new java.util.HashSet<>();
		for (SysDictBiz d : matched) {
			if (d.getParentId() != null && d.getParentId() != 0L && !ids.contains(d.getParentId())) {
				up.add(d.getParentId());
			}
		}
		while (!up.isEmpty()) {
			List<SysDictBiz> parents = dictBizMapper.selectListByQuery(QueryWrapper.create().in("id", up));
			up.clear();
			for (SysDictBiz p : parents) {
				if (ids.add(p.getId()) && p.getParentId() != null && p.getParentId() != 0L
					&& !ids.contains(p.getParentId())) {
					up.add(p.getParentId());
				}
			}
		}
		// 向下：逐层补子孙（同上防环）
		java.util.Set<Long> down = new java.util.HashSet<>(ids);
		while (!down.isEmpty()) {
			List<SysDictBiz> children = dictBizMapper.selectListByQuery(QueryWrapper.create().in("parent_id", down));
			down.clear();
			for (SysDictBiz c : children) {
				if (ids.add(c.getId())) {
					down.add(c.getId());
				}
			}
		}
		return dictBizMapper.selectListByQuery(QueryWrapper.create().in("id", ids).orderBy("sort", true));
	}

	@GetMapping("/dictionary")
	public R<List<SysDictBiz>> dictionary(@RequestParam String code) {
		return R.data(dictBizMapper.selectListByQuery(QueryWrapper.create()
			.eq("code", code).ne("parent_id", 0L).orderBy("sort", true)));
	}

	@GetMapping("/detail")
	public R<SysDictBiz> detail(@RequestParam Long id) {
		return R.data(dictBizMapper.selectOneById(id));
	}

	@SaCheckPermission("sys:dict-biz:save")
	@PostMapping("/submit")
	public R<Void> submit(@RequestBody SysDictBiz dict) {
		// 字典类型无键值：dictKey 兜底空串，避免 NOT NULL 约束报错
		if (dict.getDictKey() == null) {
			dict.setDictKey("");
		}
		if (dict.getId() == null) {
			dictBizMapper.insertSelective(dict);
		} else {
			dictBizMapper.update(dict);
		}
		return R.success("操作成功");
	}

	@SaCheckPermission("sys:dict-biz:remove")
	@PostMapping("/remove")
	public R<Void> remove(@RequestBody List<Long> ids) {
		dictBizMapper.deleteBatchByIds(ids);
		return R.success("删除成功");
	}
}
