package com.mugsun.boot.system.controller;

import cn.dev33.satoken.annotation.SaCheckLogin;
import cn.dev33.satoken.annotation.SaCheckPermission;
import com.mugsun.boot.common.repeat.RepeatSubmit;
import com.mugsun.boot.system.entity.SysDictBiz;
import com.mugsun.boot.system.mapper.SysDictBizMapper;
import com.mugsun.boot.tenant.TenantContext;
import com.mugsun.core.tool.api.R;
import com.mugsun.core.tool.exception.ServiceException;
import com.mugsun.core.tool.tree.TreeUtil;
import com.mybatisflex.core.query.QueryWrapper;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 业务字典管理。按租户隔离，和系统字典不是同一套数据。
 */
@RestController
@RequestMapping("/system/dict-biz")
@SaCheckLogin
public class SysDictBizController {

	private static final Pattern CODE = Pattern.compile("^[A-Za-z][A-Za-z0-9_-]{0,63}$");
	private static final Pattern KEY = Pattern.compile("^[A-Za-z0-9_.:-]{1,64}$");

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
		SysDictBiz dict = dictBizMapper.selectOneById(id);
		if (dict == null) {
			throw new ServiceException("这条业务字典不存在，或属于其他租户。请刷新列表后再看");
		}
		return R.data(dict);
	}

	@SaCheckPermission("sys:dict-biz:save")
	@RepeatSubmit
	@PostMapping("/submit")
	public R<Void> submit(@RequestBody SysDictBiz dict) {
		if (dict == null) {
			throw new ServiceException("请填写业务字典内容后再保存");
		}
		String value = dict.getDictValue() == null ? "" : dict.getDictValue().trim();
		if (value.isEmpty()) {
			throw new ServiceException("请填写字典名称");
		}
		if (value.length() > 128) {
			throw new ServiceException("字典名称请控制在 128 字以内");
		}
		dict.setDictValue(value);
		String code = dict.getCode() == null ? "" : dict.getCode().trim();
		if (code.isEmpty()) {
			throw new ServiceException("请填写字典编码");
		}
		if (!CODE.matcher(code).matches()) {
			throw new ServiceException("字典编码请用字母开头，只能包含字母、数字、下划线和中横线");
		}
		dict.setCode(code);
		long parentId = dict.getParentId() == null ? 0L : dict.getParentId();
		dict.setParentId(parentId);
		String key = dict.getDictKey() == null ? "" : dict.getDictKey().trim();
		if (parentId == 0L && key.isEmpty()) {
			key = code;
		}
		if (key.isEmpty()) {
			throw new ServiceException("请填写字典键值。下级字典要靠键值区分");
		}
		if (!KEY.matcher(key).matches()) {
			throw new ServiceException("字典键值只能包含字母、数字、下划线、点和中横线");
		}
		dict.setDictKey(key);
		if (dict.getSort() == null) {
			dict.setSort(0);
		}
		if (dict.getSort() < 0 || dict.getSort() > 9999) {
			throw new ServiceException("排序请填 0 到 9999 的整数");
		}
		if (dict.getRemark() != null && dict.getRemark().length() > 255) {
			throw new ServiceException("备注请控制在 255 字以内");
		}
		if (dict.getColor() != null && dict.getColor().trim().length() > 32) {
			throw new ServiceException("标签颜色请控制在 32 字以内");
		}
		if (dict.getColor() != null) {
			dict.setColor(dict.getColor().trim());
		}
		if (dict.getIsSealed() == null) {
			dict.setIsSealed(0);
		}
		if (dict.getIsSealed() != 0 && dict.getIsSealed() != 1) {
			throw new ServiceException("封存只能选已封存或未封存");
		}
		assertParent(dict);
		QueryWrapper dup = QueryWrapper.create().eq("code", code).eq("dict_key", key);
		if (dict.getId() != null) {
			dup.ne("id", dict.getId());
		}
		if (dictBizMapper.selectCountByQuery(dup) > 0) {
			throw new ServiceException("编码「" + code + "」下已经有键值「" + key + "」。请换一个键值");
		}
		dict.setTenantId(TenantContext.current());
		if (dict.getId() == null) {
			dict.sanitizeForInsert();
			dictBizMapper.insertSelective(dict);
		} else {
			SysDictBiz existing = dictBizMapper.selectOneById(dict.getId());
			if (existing == null) {
				throw new ServiceException("这条业务字典不存在，或属于其他租户。请刷新列表后再改");
			}
			if (!code.equals(existing.getCode())) {
				throw new ServiceException("字典编码创建后不能修改。请新增一条，再删除原来的");
			}
			if (!key.equals(existing.getDictKey() == null ? "" : existing.getDictKey())) {
				throw new ServiceException("字典键值创建后不能修改。请新增一条，再删除原来的");
			}
			dict.setTenantId(existing.getTenantId());
			dict.sanitizeForUpdate();
			dictBizMapper.update(dict);
		}
		return R.success("已保存。可以在列表里查看这条字典");
	}

	@SaCheckPermission("sys:dict-biz:remove")
	@RepeatSubmit
	@PostMapping("/remove")
	public R<Void> remove(@RequestBody List<Long> ids) {
		if (ids == null || ids.isEmpty()) {
			throw new ServiceException("请先勾选要删除的字典");
		}
		List<SysDictBiz> rows = new ArrayList<>();
		for (Long id : ids) {
			if (id == null) {
				continue;
			}
			SysDictBiz dict = dictBizMapper.selectOneById(id);
			if (dict == null) {
				continue;
			}
			if (dict.getIsSealed() != null && dict.getIsSealed() == 1) {
				throw new ServiceException("「" + dict.getDictValue() + "」已封存。请先编辑并取消封存，再删除");
			}
			if (dictBizMapper.selectCountByQuery(QueryWrapper.create().eq("parent_id", id)) > 0) {
				throw new ServiceException("「" + dict.getDictValue() + "」下面还有下级字典。请先删除下级，再删除这一级");
			}
			rows.add(dict);
		}
		if (rows.isEmpty()) {
			throw new ServiceException("这些业务字典不存在，或属于其他租户。请刷新列表后再删");
		}
		for (SysDictBiz dict : rows) {
			dictBizMapper.deleteById(dict.getId());
		}
		return R.success("已删除。列表里不会再出现这些字典");
	}

	private void assertParent(SysDictBiz dict) {
		long parentId = dict.getParentId();
		if (parentId == 0L) {
			return;
		}
		if (dict.getId() != null && parentId == dict.getId()) {
			throw new ServiceException("上级字典不能是自己。请改成顶级，或选另一个字典");
		}
		SysDictBiz parent = dictBizMapper.selectOneById(parentId);
		if (parent == null) {
			throw new ServiceException("上级字典不存在，或属于其他租户。请刷新后再选");
		}
		if (!dict.getCode().equals(parent.getCode())) {
			throw new ServiceException("下级字典的编码要和上级「" + parent.getDictValue() + "」一致。请改成 " + parent.getCode());
		}
		if (dict.getId() == null) {
			return;
		}
		Set<Long> down = new HashSet<>();
		down.add(dict.getId());
		int guard = 0;
		while (!down.isEmpty() && guard++ < 64) {
			List<SysDictBiz> children = dictBizMapper.selectListByQuery(QueryWrapper.create().in("parent_id", down));
			down.clear();
			for (SysDictBiz child : children) {
				if (child.getId() != null && child.getId() == parentId) {
					throw new ServiceException("上级不能选自己的下级。请改成顶级，或选另一个字典");
				}
				if (child.getId() != null) {
					down.add(child.getId());
				}
			}
		}
	}
}
