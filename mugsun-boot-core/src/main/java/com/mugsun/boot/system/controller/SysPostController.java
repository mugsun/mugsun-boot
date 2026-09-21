package com.mugsun.boot.system.controller;

import cn.dev33.satoken.annotation.SaCheckLogin;
import cn.dev33.satoken.annotation.SaCheckPermission;
import com.mugsun.boot.system.entity.SysPost;
import com.mugsun.boot.system.mapper.SysPostMapper;
import com.mugsun.core.tool.api.R;
import com.mybatisflex.core.paginate.Page;
import com.mybatisflex.core.query.QueryWrapper;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 岗位管理
 */
@RestController
@RequestMapping("/system/post")
@SaCheckLogin
public class SysPostController {

	private final SysPostMapper postMapper;

	public SysPostController(SysPostMapper postMapper) {
		this.postMapper = postMapper;
	}

	@GetMapping("/page")
	public R<Page<SysPost>> page(@RequestParam(defaultValue = "1") long pageNum,
								 @RequestParam(defaultValue = "10") long pageSize,
								 @RequestParam(required = false) String postName,
								 @RequestParam(required = false) String postCode) {
		QueryWrapper query = QueryWrapper.create().orderBy("sort", true);
		// 查询条件（值走参数化绑定，LIKE 前后模糊）
		if (postName != null && !postName.isBlank()) {
			query.like("post_name", postName.trim());
		}
		if (postCode != null && !postCode.isBlank()) {
			query.like("post_code", postCode.trim());
		}
		return R.data(postMapper.paginate(pageNum, pageSize, query));
	}

	/** 岗位下拉选项（value/label 契约） */
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

	@GetMapping("/detail")
	public R<SysPost> detail(@RequestParam Long id) {
		return R.data(postMapper.selectOneById(id));
	}

	@SaCheckPermission("sys:post:save")
	@PostMapping("/submit")
	public R<Void> submit(@RequestBody SysPost post) {
		if (post.getId() == null) {
			post.sanitizeForInsert();
			post.setTenantId(null);
			postMapper.insert(post);
		} else {
			post.sanitizeForUpdate();
			post.setTenantId(null);
			postMapper.update(post);
		}
		return R.success("操作成功");
	}

	@SaCheckPermission("sys:post:remove")
	@PostMapping("/remove")
	public R<Void> remove(@RequestBody List<Long> ids) {
		postMapper.deleteBatchByIds(ids);
		return R.success("删除成功");
	}
}
