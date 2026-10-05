package com.mugsun.boot.system.controller;

import cn.dev33.satoken.annotation.SaCheckLogin;
import cn.dev33.satoken.annotation.SaCheckPermission;
import cn.dev33.satoken.annotation.SaMode;
import com.mugsun.boot.common.repeat.RepeatSubmit;
import com.mugsun.boot.system.entity.SysPost;
import com.mugsun.boot.system.mapper.SysPostMapper;
import com.mugsun.boot.system.mapper.SysUserMapper;
import com.mugsun.core.tool.api.R;
import com.mugsun.core.tool.exception.ServiceException;
import com.mybatisflex.core.paginate.Page;
import com.mybatisflex.core.query.QueryWrapper;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 岗位管理
 */
@RestController
@RequestMapping("/system/post")
@SaCheckLogin
public class SysPostController {

	private static final Set<String> CATEGORIES = Set.of("manage", "tech", "biz");

	private final SysPostMapper postMapper;
	private final SysUserMapper userMapper;

	public SysPostController(SysPostMapper postMapper, SysUserMapper userMapper) {
		this.postMapper = postMapper;
		this.userMapper = userMapper;
	}

	@SaCheckPermission(value = {"sys:post:save", "sys:post:remove"}, mode = SaMode.OR)
	@GetMapping("/page")
	public R<Page<SysPost>> page(@RequestParam(defaultValue = "1") long pageNum,
								 @RequestParam(defaultValue = "10") long pageSize,
								 @RequestParam(required = false) String postName,
								 @RequestParam(required = false) String postCode,
								 @RequestParam(required = false) String category) {
		QueryWrapper query = QueryWrapper.create().orderBy("sort", true).orderBy("id", false);
		if (postName != null && !postName.isBlank()) {
			query.like("post_name", postName.trim());
		}
		if (postCode != null && !postCode.isBlank()) {
			query.like("post_code", postCode.trim());
		}
		if (category != null && !category.isBlank()) {
			if (!CATEGORIES.contains(category)) {
				throw new ServiceException("岗位类型请选择管理、技术或业务");
			}
			query.eq("category", category);
		}
		return R.data(postMapper.paginate(pageNum, Math.min(pageSize, 500), query));
	}

	/** 岗位下拉选项（value/label 契约） */
	@SaCheckPermission(value = {"sys:post:save", "sys:post:remove", "sys:user:list"}, mode = SaMode.OR)
	@GetMapping("/select")
	public R<List<Map<String, Object>>> select() {
		List<Map<String, Object>> options = postMapper
			.selectListByQuery(QueryWrapper.create().orderBy("sort", true))
			.stream()
			.map(post -> {
				Map<String, Object> option = new HashMap<>();
				option.put("value", post.getId());
				option.put("label", post.getPostName());
				return option;
			})
			.toList();
		return R.data(options);
	}

	@SaCheckPermission(value = {"sys:post:save", "sys:post:remove"}, mode = SaMode.OR)
	@GetMapping("/detail")
	public R<SysPost> detail(@RequestParam Long id) {
		SysPost post = postMapper.selectOneById(id);
		if (post == null) {
			throw new ServiceException("这个岗位不存在，或属于其他租户。请刷新列表后再看");
		}
		return R.data(post);
	}

	@RepeatSubmit(message = "请勿重复保存。请到列表里确认这个岗位是否已经提交")
	@SaCheckPermission("sys:post:save")
	@PostMapping("/submit")
	public R<Void> submit(@RequestBody SysPost post) {
		if (post == null || post.getPostName() == null || post.getPostName().isBlank()) {
			throw new ServiceException("请填写岗位名称");
		}
		if (post.getPostName().trim().length() > 64) {
			throw new ServiceException("岗位名称请控制在 64 字以内");
		}
		post.setPostName(post.getPostName().trim());
		if (post.getPostCode() == null || !post.getPostCode().trim().matches("^[A-Za-z][A-Za-z0-9_-]{0,63}$")) {
			throw new ServiceException("岗位编码请使用字母开头，只能包含字母、数字、下划线或短横线");
		}
		post.setPostCode(post.getPostCode().trim());
		if (post.getCategory() == null || !CATEGORIES.contains(post.getCategory())) {
			throw new ServiceException("请选择管理、技术或业务");
		}
		if (post.getSort() == null) {
			post.setSort(0);
		}
		if (post.getSort() < 0 || post.getSort() > 9999) {
			throw new ServiceException("排序请填写 0 到 9999 之间的数字");
		}
		assertCodeFree(post.getPostCode(), post.getId());
		if (post.getId() == null) {
			post.sanitizeForInsert();
			post.setTenantId(null);
			postMapper.insert(post);
		} else {
			SysPost exist = postMapper.selectOneById(post.getId());
			if (exist == null) {
				throw new ServiceException("这个岗位不存在，或属于其他租户。请刷新列表后再编辑");
			}
			post.sanitizeForUpdate();
			post.setTenantId(null);
			postMapper.update(post);
		}
		return R.success("已保存。可以在列表里查看这个岗位");
	}

	@RepeatSubmit(message = "请勿重复删除。请刷新列表确认这些岗位是否还在")
	@SaCheckPermission("sys:post:remove")
	@PostMapping("/remove")
	@Transactional(rollbackFor = Exception.class)
	public R<Void> remove(@RequestBody List<Long> ids) {
		if (ids == null || ids.isEmpty()) {
			throw new ServiceException("请先勾选要删除的岗位");
		}
		int removed = 0;
		for (Long id : ids) {
			if (id == null) {
				continue;
			}
			SysPost exist = postMapper.selectOneById(id);
			if (exist == null) {
				continue;
			}
			long using = userMapper.selectCountByQuery(QueryWrapper.create().eq("post_id", id));
			if (using > 0) {
				throw new ServiceException("还有人在用「" + exist.getPostName() + "」。请先到用户管理里换掉他们的岗位，再删除");
			}
			postMapper.deleteById(id);
			removed++;
		}
		if (removed == 0) {
			throw new ServiceException("这些岗位不存在，或已被删除。请刷新列表后再试");
		}
		return R.success("已删除。列表里不会再出现这些岗位");
	}

	private void assertCodeFree(String postCode, Long id) {
		QueryWrapper query = QueryWrapper.create().eq("post_code", postCode);
		if (id != null) {
			query.ne("id", id);
		}
		if (postMapper.selectCountByQuery(query) > 0) {
			throw new ServiceException("岗位编码已存在。请换一个编码，或在列表里编辑原来那个岗位");
		}
	}
}
