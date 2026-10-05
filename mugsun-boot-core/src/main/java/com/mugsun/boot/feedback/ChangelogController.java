package com.mugsun.boot.feedback;

import cn.dev33.satoken.annotation.SaCheckLogin;
import cn.dev33.satoken.annotation.SaCheckPermission;
import com.mugsun.boot.common.repeat.RepeatSubmit;
import com.mugsun.boot.feedback.entity.SysChangelog;
import com.mugsun.boot.feedback.mapper.SysChangelogMapper;
import com.mugsun.core.tool.api.R;
import com.mugsun.core.tool.exception.ServiceException;
import com.mybatisflex.core.paginate.Page;
import com.mybatisflex.core.query.QueryWrapper;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Set;

/**
 * 版本更新记录：管理员维护，首页展示最近更新。
 */
@RestController
@RequestMapping("/system/changelog")
@SaCheckLogin
public class ChangelogController {

	private static final Set<String> TYPES = Set.of("feature", "optimize", "fix");

	private final SysChangelogMapper changelogMapper;

	public ChangelogController(SysChangelogMapper changelogMapper) {
		this.changelogMapper = changelogMapper;
	}

	/** 首页更新日志卡：最近若干条（不返 content 大字段） */
	@GetMapping("/recent")
	public R<List<SysChangelog>> recent(@RequestParam(defaultValue = "5") int limit) {
		int size = Math.min(Math.max(limit, 1), 20);
		return R.data(changelogMapper.selectListByQuery(QueryWrapper.create()
			.select("id", "version", "type", "title", "publish_time")
			.orderBy("publish_time", false).orderBy("sort", false).orderBy("id", false)
			.limit(size)));
	}

	@GetMapping("/page")
	@SaCheckPermission("sys:changelog:manage")
	public R<Page<SysChangelog>> page(@RequestParam(defaultValue = "1") long pageNum,
									  @RequestParam(defaultValue = "10") long pageSize,
									  @RequestParam(required = false) String type,
									  @RequestParam(required = false) String name) {
		QueryWrapper query = QueryWrapper.create().orderBy("publish_time", false).orderBy("id", false);
		if (type != null && !type.isBlank()) {
			String value = type.trim();
			if (!TYPES.contains(value)) {
				throw new ServiceException("类型请选择新增、优化或修复");
			}
			query.eq("type", value);
		}
		if (name != null && !name.isBlank()) {
			query.like("title", name.trim());
		}
		return R.data(changelogMapper.paginate(pageNum, pageSize, query));
	}

	@GetMapping("/detail")
	@SaCheckPermission("sys:changelog:manage")
	public R<SysChangelog> detail(@RequestParam(required = false) Long id) {
		if (id == null) {
			throw new ServiceException("请从列表中选择一条更新记录后再查看");
		}
		SysChangelog row = changelogMapper.selectOneById(id);
		if (row == null) {
			throw new ServiceException("这条更新记录不存在。请刷新列表后再看");
		}
		return R.data(row);
	}

	@PostMapping("/submit")
	@SaCheckPermission("sys:changelog:manage")
	@RepeatSubmit
	public R<Void> submit(@RequestBody SysChangelog changelog) {
		if (changelog == null) {
			throw new ServiceException("请填写版本号");
		}
		boolean creating = changelog.getId() == null;
		normalize(changelog);
		if (creating) {
			if (changelog.getPublishTime() == null) {
				changelog.setPublishTime(java.time.LocalDateTime.now());
			}
			changelogMapper.insert(changelog);
			return R.success("已保存。列表和首页更新日志里都能看到这条记录");
		}
		SysChangelog before = changelogMapper.selectOneById(changelog.getId());
		if (before == null) {
			throw new ServiceException("这条更新记录不存在。请刷新列表后再编辑");
		}
		if (changelog.getPublishTime() == null) {
			changelog.setPublishTime(before.getPublishTime());
		}
		changelogMapper.update(changelog);
		return R.success("已保存。列表和首页更新日志里都能看到这条记录");
	}

	@PostMapping("/remove")
	@SaCheckPermission("sys:changelog:manage")
	@RepeatSubmit
	public R<Void> remove(@RequestBody List<Long> ids) {
		if (ids == null || ids.isEmpty()) {
			throw new ServiceException("请先选择要删除的更新记录");
		}
		if (ids.stream().anyMatch(id -> id == null)) {
			throw new ServiceException("有记录不存在。请刷新列表后再删除");
		}
		List<SysChangelog> rows = changelogMapper.selectListByIds(ids);
		if (rows.size() != ids.size()) {
			throw new ServiceException("有记录不存在，或已经被删除。请刷新列表后再试");
		}
		for (SysChangelog row : rows) {
			changelogMapper.deleteById(row.getId());
		}
		return R.success("已删除 " + rows.size() + " 条记录。列表和首页都不会再出现");
	}

	private void normalize(SysChangelog changelog) {
		String version = changelog.getVersion() == null ? "" : changelog.getVersion().trim();
		if (version.isEmpty()) {
			throw new ServiceException("请填写版本号");
		}
		if (!version.matches("^v?\\d{1,4}(\\.\\d{1,4}){0,3}$")) {
			throw new ServiceException("版本号请写成 v1.2.0 这种形式");
		}
		changelog.setVersion(version);
		String type = changelog.getType() == null ? "" : changelog.getType().trim();
		if (!TYPES.contains(type)) {
			throw new ServiceException("类型请选择新增、优化或修复");
		}
		changelog.setType(type);
		String title = changelog.getTitle() == null ? "" : changelog.getTitle().trim();
		if (title.isEmpty()) {
			throw new ServiceException("请填写标题");
		}
		if (title.length() > 255) {
			throw new ServiceException("标题请控制在 255 个字以内");
		}
		changelog.setTitle(title);
		String content = changelog.getContent() == null ? "" : changelog.getContent().trim();
		if (content.length() > 20000) {
			throw new ServiceException("内容请控制在 20000 个字以内");
		}
		changelog.setContent(content.isEmpty() ? null : content);
		Integer sort = changelog.getSort();
		if (sort == null) {
			changelog.setSort(0);
		} else if (sort < 0 || sort > 9999) {
			throw new ServiceException("排序请填写 0 到 9999 的整数");
		}
	}
}
