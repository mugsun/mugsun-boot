package com.mugsun.boot.feedback;

import cn.dev33.satoken.annotation.SaCheckLogin;
import cn.dev33.satoken.annotation.SaCheckPermission;
import com.mugsun.boot.feedback.entity.SysChangelog;
import com.mugsun.boot.feedback.mapper.SysChangelogMapper;
import com.mugsun.core.tool.api.R;
import com.mybatisflex.core.paginate.Page;
import com.mybatisflex.core.query.QueryWrapper;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 版本更新记录：管理员维护，首页展示最近更新。
 */
@RestController
@RequestMapping("/system/changelog")
@SaCheckLogin
public class ChangelogController {

	private final SysChangelogMapper changelogMapper;

	public ChangelogController(SysChangelogMapper changelogMapper) {
		this.changelogMapper = changelogMapper;
	}

	/** 首页更新日志卡：最近若干条（不返 content 大字段） */
	@GetMapping("/recent")
	public R<List<SysChangelog>> recent(@RequestParam(defaultValue = "5") int limit) {
		return R.data(changelogMapper.selectListByQuery(QueryWrapper.create()
			.select("id", "version", "type", "title", "publish_time")
			.orderBy("publish_time", false).orderBy("sort", false).orderBy("id", false)
			.limit(limit)));
	}

	@GetMapping("/page")
	public R<Page<SysChangelog>> page(@RequestParam(defaultValue = "1") long pageNum,
									  @RequestParam(defaultValue = "10") long pageSize,
									  @RequestParam(required = false) String type) {
		QueryWrapper query = QueryWrapper.create().orderBy("sort", false).orderBy("id", false);
		if (type != null && !type.isBlank()) {
			query.eq("type", type);
		}
		return R.data(changelogMapper.paginate(pageNum, pageSize, query));
	}

	@GetMapping("/detail")
	public R<SysChangelog> detail(@RequestParam Long id) {
		return R.data(changelogMapper.selectOneById(id));
	}

	@PostMapping("/submit")
	@SaCheckPermission("sys:changelog:manage")
	public R<Void> submit(@RequestBody SysChangelog changelog) {
		if (changelog.getId() == null) {
			// 未指定发布时间则以当前时间发布，保证首页按时间排序稳定
			if (changelog.getPublishTime() == null) {
				changelog.setPublishTime(java.time.LocalDateTime.now());
			}
			changelogMapper.insertSelective(changelog);
		} else {
			changelogMapper.update(changelog);
		}
		return R.success("操作成功");
	}

	@PostMapping("/remove")
	@SaCheckPermission("sys:changelog:manage")
	public R<Void> remove(@RequestBody List<Long> ids) {
		changelogMapper.deleteBatchByIds(ids);
		return R.success("删除成功");
	}
}
