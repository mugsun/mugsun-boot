package com.mugsun.boot.help;

import cn.dev33.satoken.annotation.SaCheckLogin;
import cn.dev33.satoken.annotation.SaCheckPermission;
import com.mugsun.boot.common.repeat.RepeatSubmit;
import com.mugsun.boot.help.entity.HelpCatalog;
import com.mugsun.boot.help.entity.HelpDoc;
import com.mugsun.boot.help.entity.HelpPageBinding;
import com.mugsun.boot.help.mapper.HelpCatalogMapper;
import com.mugsun.boot.help.mapper.HelpDocMapper;
import com.mugsun.boot.help.mapper.HelpPageBindingMapper;
import com.mugsun.core.tool.api.R;
import com.mugsun.core.tool.exception.ServiceException;
import com.mugsun.core.tool.tree.TreeUtil;
import com.mybatisflex.core.paginate.Page;
import com.mybatisflex.core.query.QueryWrapper;
import com.mybatisflex.core.row.Db;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 在线帮助文档：目录树 + 文档(富文本) + 页面绑定 + 前台按路由查阅与浏览量统计。
 */
@RestController
@RequestMapping("/system/help")
@SaCheckLogin
public class HelpController {

	private static final int NAME_LIMIT = 64;
	private static final int TITLE_LIMIT = 255;
	private static final int CONTENT_LIMIT = 20000;
	private static final int ROUTE_LIMIT = 255;
	private static final int SORT_LIMIT = 9999;

	private final HelpCatalogMapper catalogMapper;
	private final HelpDocMapper docMapper;
	private final HelpPageBindingMapper bindingMapper;

	public HelpController(HelpCatalogMapper catalogMapper, HelpDocMapper docMapper,
						  HelpPageBindingMapper bindingMapper) {
		this.catalogMapper = catalogMapper;
		this.docMapper = docMapper;
		this.bindingMapper = bindingMapper;
	}

	@GetMapping("/catalog/tree")
	public R<List<HelpCatalog>> catalogTree() {
		List<HelpCatalog> all = catalogMapper.selectListByQuery(QueryWrapper.create().orderBy("sort", true));
		return R.data(TreeUtil.build(all, 0L));
	}

	@PostMapping("/catalog/submit")
	@SaCheckPermission("sys:help:manage")
	@RepeatSubmit
	public R<Void> catalogSubmit(@RequestBody HelpCatalog catalog) {
		if (catalog == null) {
			throw new ServiceException("请填写目录名称");
		}
		String name = catalog.getName() == null ? "" : catalog.getName().trim();
		if (name.isEmpty()) {
			throw new ServiceException("请填写目录名称");
		}
		if (name.length() > NAME_LIMIT) {
			throw new ServiceException("目录名称请控制在 64 个字以内");
		}
		int sort = normalizeSort(catalog.getSort());
		long parentId = catalog.getParentId() == null ? 0L : catalog.getParentId();
		if (parentId != 0L && catalogMapper.selectOneById(parentId) == null) {
			throw new ServiceException("上级目录不存在。请刷新目录树后再保存");
		}
		if (catalog.getId() != null) {
			HelpCatalog existing = catalogMapper.selectOneById(catalog.getId());
			if (existing == null) {
				throw new ServiceException("这个目录不存在。请刷新目录树后再编辑");
			}
			assertNotMovingUnderSelf(catalog.getId(), parentId);
		}
		QueryWrapper sameName = QueryWrapper.create().eq("parent_id", parentId).eq("name", name);
		if (catalog.getId() != null) {
			sameName.ne("id", catalog.getId());
		}
		if (catalogMapper.selectCountByQuery(sameName) > 0) {
			throw new ServiceException("同一级里已经有叫「" + name + "」的目录。请换一个名称，或编辑原来那个");
		}
		catalog.setName(name);
		catalog.setSort(sort);
		catalog.setParentId(parentId);
		if (catalog.getId() == null) {
			catalogMapper.insertSelective(catalog);
			return R.success("已新增目录。左侧目录树里可以看到");
		}
		catalogMapper.update(catalog);
		return R.success("已保存。左侧目录树会显示新的名称");
	}

	@PostMapping("/catalog/remove/{id}")
	@SaCheckPermission("sys:help:manage")
	@RepeatSubmit
	public R<Void> catalogRemove(@PathVariable Long id) {
		HelpCatalog existing = catalogMapper.selectOneById(id);
		if (existing == null) {
			throw new ServiceException("这个目录不存在。请刷新目录树后再删除");
		}
		if (catalogMapper.selectCountByQuery(QueryWrapper.create().eq("parent_id", id)) > 0
			|| docMapper.selectCountByQuery(QueryWrapper.create().eq("catalog_id", id)) > 0) {
			throw new ServiceException("这个目录下面还有子目录或文档。请先删掉它们，再删这个目录");
		}
		catalogMapper.deleteById(id);
		return R.success("已删除目录「" + existing.getName() + "」。左侧不会再出现");
	}

	/** 文档分页：列表不返 content 大字段 */
	@GetMapping("/doc/page")
	public R<Page<HelpDoc>> docPage(@RequestParam(defaultValue = "1") long pageNum,
									@RequestParam(defaultValue = "10") long pageSize,
									@RequestParam(required = false) Long catalogId,
									@RequestParam(required = false) String title) {
		if (pageNum < 1 || pageSize < 1 || pageSize > 100) {
			throw new ServiceException("页码从 1 开始，每页最多 100 条");
		}
		QueryWrapper query = QueryWrapper.create()
			.select("id", "catalog_id", "title", "view_count", "sort", "create_time", "update_time")
			.orderBy("sort", true).orderBy("id", false);
		if (catalogId != null) {
			query.eq("catalog_id", catalogId);
		}
		if (title != null && !title.isBlank()) {
			query.like("title", title.trim());
		}
		return R.data(docMapper.paginate(pageNum, pageSize, query));
	}

	@GetMapping("/doc/detail")
	public R<HelpDoc> docDetail(@RequestParam Long id) {
		HelpDoc doc = docMapper.selectOneById(id);
		if (doc == null) {
			throw new ServiceException("这篇文档不存在。请刷新列表后再看");
		}
		return R.data(doc);
	}

	@PostMapping("/doc/submit")
	@SaCheckPermission("sys:help:manage")
	@RepeatSubmit
	public R<Void> docSubmit(@RequestBody HelpDoc doc) {
		if (doc == null) {
			throw new ServiceException("请填写文档标题");
		}
		String title = doc.getTitle() == null ? "" : doc.getTitle().trim();
		if (title.isEmpty()) {
			throw new ServiceException("请填写文档标题");
		}
		if (title.length() > TITLE_LIMIT) {
			throw new ServiceException("文档标题请控制在 255 个字以内");
		}
		String content = doc.getContent() == null ? "" : doc.getContent();
		if (content.length() > CONTENT_LIMIT) {
			throw new ServiceException("文档内容请控制在 20000 个字以内");
		}
		int sort = normalizeSort(doc.getSort());
		if (doc.getCatalogId() == null || catalogMapper.selectOneById(doc.getCatalogId()) == null) {
			throw new ServiceException("请先选择一个还在的目录。目录被删掉时，刷新左侧后再保存");
		}
		doc.setTitle(title);
		doc.setContent(content);
		doc.setSort(sort);
		if (doc.getId() == null) {
			doc.setViewCount(0L);
			docMapper.insertSelective(doc);
			return R.success("已新增文档。在左侧选中的目录下可以打开");
		}
		HelpDoc existing = docMapper.selectOneById(doc.getId());
		if (existing == null) {
			throw new ServiceException("这篇文档不存在。请刷新列表后再编辑");
		}
		doc.setViewCount(existing.getViewCount());
		docMapper.update(doc);
		return R.success("已保存。打开这篇文档能看到新的标题和内容");
	}

	@PostMapping("/doc/remove")
	@SaCheckPermission("sys:help:manage")
	@RepeatSubmit
	public R<Void> docRemove(@RequestBody List<Long> ids) {
		if (ids == null || ids.isEmpty()) {
			throw new ServiceException("请先选择要删除的文档");
		}
		List<Long> distinct = new ArrayList<>();
		for (Long id : ids) {
			if (id == null) {
				throw new ServiceException("有文档不存在。请刷新列表后再删除");
			}
			if (!distinct.contains(id)) {
				distinct.add(id);
			}
		}
		List<HelpDoc> existing = docMapper.selectListByIds(distinct);
		if (existing.size() != distinct.size()) {
			throw new ServiceException("有文档不存在，或已经被删除。请刷新列表后再试");
		}
		docMapper.deleteBatchByIds(distinct);
		bindingMapper.deleteByQuery(QueryWrapper.create().in("doc_id", distinct));
		return R.success("已删除 " + distinct.size() + " 篇文档。列表和已绑定的页面都不会再出现");
	}

	@GetMapping("/binding/list")
	public R<List<HelpPageBinding>> bindingList(@RequestParam Long docId) {
		if (docMapper.selectOneById(docId) == null) {
			throw new ServiceException("这篇文档不存在。请刷新列表后再看绑定");
		}
		return R.data(bindingMapper.selectListByQuery(
			QueryWrapper.create().eq("doc_id", docId).orderBy("sort", true)));
	}

	@PostMapping("/binding/submit")
	@SaCheckPermission("sys:help:manage")
	@RepeatSubmit
	public R<Void> bindingSubmit(@RequestBody HelpPageBinding binding) {
		if (binding == null) {
			throw new ServiceException("请填写页面路由，例如 /system/user");
		}
		String route = normalizeRoute(binding.getRoutePath());
		if (binding.getDocId() == null || docMapper.selectOneById(binding.getDocId()) == null) {
			throw new ServiceException("这篇文档不存在。请刷新列表后再绑定");
		}
		int sort = normalizeSort(binding.getSort());
		binding.setRoutePath(route);
		binding.setSort(sort);
		if (binding.getId() == null) {
			if (bindingMapper.selectCountByQuery(QueryWrapper.create()
				.eq("route_path", route).eq("doc_id", binding.getDocId())) > 0) {
				throw new ServiceException("这个页面已经绑过这篇文档。换一个路由，或先解除原来的绑定");
			}
			bindingMapper.insertSelective(binding);
			return R.success("已绑定。打开「" + route + "」时，帮助里能看到这篇文档");
		}
		if (bindingMapper.selectOneById(binding.getId()) == null) {
			throw new ServiceException("这条绑定不存在。请刷新后再改");
		}
		bindingMapper.update(binding);
		return R.success("已保存绑定。打开「" + route + "」时会按新的路由显示");
	}

	@PostMapping("/binding/remove/{id}")
	@SaCheckPermission("sys:help:manage")
	@RepeatSubmit
	public R<Void> bindingRemove(@PathVariable Long id) {
		HelpPageBinding existing = bindingMapper.selectOneById(id);
		if (existing == null) {
			throw new ServiceException("这条绑定不存在。请刷新后再解除");
		}
		bindingMapper.deleteById(id);
		return R.success("已解除「" + existing.getRoutePath() + "」。这个页面的帮助里不会再出现这篇文档");
	}

	@GetMapping("/page-docs")
	public R<List<HelpDoc>> pageDocs(@RequestParam String routePath) {
		String route = routePath == null ? "" : routePath.trim();
		if (route.isEmpty()) {
			throw new ServiceException("请带上页面路由后再查帮助，例如 /system/user");
		}
		List<HelpPageBinding> bindings = bindingMapper.selectListByQuery(
			QueryWrapper.create().eq("route_path", route).orderBy("sort", true));
		if (bindings.isEmpty()) {
			return R.data(List.of());
		}
		List<Long> docIds = bindings.stream().map(HelpPageBinding::getDocId).toList();
		Map<Long, HelpDoc> byId = docMapper
			.selectListByQuery(QueryWrapper.create().select("id", "title", "view_count").in("id", docIds))
			.stream().collect(Collectors.toMap(HelpDoc::getId, Function.identity()));
		List<HelpDoc> ordered = docIds.stream().map(byId::get).filter(Objects::nonNull).toList();
		return R.data(ordered);
	}

	@PostMapping("/view/{id}")
	public R<HelpDoc> view(@PathVariable Long id) {
		HelpDoc existing = docMapper.selectOneById(id);
		if (existing == null) {
			throw new ServiceException("这篇文档不存在。请回到目录重新打开");
		}
		Db.updateBySql("update help_doc set view_count = view_count + 1 where id = ? and is_deleted = 0", id);
		return R.data(docMapper.selectOneById(id));
	}

	private int normalizeSort(Integer sort) {
		if (sort == null) {
			return 0;
		}
		if (sort < 0 || sort > SORT_LIMIT) {
			throw new ServiceException("排序请填写 0 到 9999 的整数");
		}
		return sort;
	}

	private String normalizeRoute(String routePath) {
		String route = routePath == null ? "" : routePath.trim();
		if (route.isEmpty()) {
			throw new ServiceException("请填写页面路由，例如 /system/user");
		}
		if (!route.startsWith("/")) {
			throw new ServiceException("页面路由要以 / 开头，例如 /system/user");
		}
		if (route.length() > ROUTE_LIMIT) {
			throw new ServiceException("页面路由请控制在 255 个字符以内");
		}
		return route;
	}

	/** 不能把目录挂到自己或自己的下级下面，否则树会绕圈。 */
	private void assertNotMovingUnderSelf(Long id, long parentId) {
		long cursor = parentId;
		int guard = 0;
		while (cursor != 0L && guard++ < 32) {
			if (cursor == id) {
				throw new ServiceException("不能把目录移到自己或下级下面。请换一个上级");
			}
			HelpCatalog parent = catalogMapper.selectOneById(cursor);
			if (parent == null || parent.getParentId() == null) {
				return;
			}
			cursor = parent.getParentId();
		}
	}
}
