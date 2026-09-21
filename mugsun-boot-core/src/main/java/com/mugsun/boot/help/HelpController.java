package com.mugsun.boot.help;

import cn.dev33.satoken.annotation.SaCheckLogin;
import cn.dev33.satoken.annotation.SaCheckPermission;
import cn.hutool.core.util.IdUtil;
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

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 在线帮助文档：目录树 + 文档(富文本) + 页面绑定 + 前台按路由查阅与浏览量统计。
 */
@RestController
@RequestMapping("/system/help")
@SaCheckLogin
public class HelpController {

	private final HelpCatalogMapper catalogMapper;
	private final HelpDocMapper docMapper;
	private final HelpPageBindingMapper bindingMapper;

	public HelpController(HelpCatalogMapper catalogMapper, HelpDocMapper docMapper,
						  HelpPageBindingMapper bindingMapper) {
		this.catalogMapper = catalogMapper;
		this.docMapper = docMapper;
		this.bindingMapper = bindingMapper;
	}

	// ------------------------- 目录（树形） -------------------------

	@GetMapping("/catalog/tree")
	public R<List<HelpCatalog>> catalogTree() {
		List<HelpCatalog> all = catalogMapper.selectListByQuery(QueryWrapper.create().orderBy("sort", true));
		return R.data(TreeUtil.build(all, 0L));
	}

	@PostMapping("/catalog/submit")
	@SaCheckPermission("sys:help:manage")
	public R<Void> catalogSubmit(@RequestBody HelpCatalog catalog) {
		if (catalog.getId() == null) {
			catalogMapper.insertSelective(catalog);
		} else {
			catalogMapper.update(catalog);
		}
		return R.success("操作成功");
	}

	@PostMapping("/catalog/remove/{id}")
	@SaCheckPermission("sys:help:manage")
	public R<Void> catalogRemove(@PathVariable Long id) {
		// 防孤儿：有子目录或文档时拒绝删除
		if (catalogMapper.selectCountByQuery(QueryWrapper.create().eq("parent_id", id)) > 0
			|| docMapper.selectCountByQuery(QueryWrapper.create().eq("catalog_id", id)) > 0) {
			throw new ServiceException("请先删除该目录下的子目录与文档");
		}
		catalogMapper.deleteById(id);
		return R.success("删除成功");
	}

	// ------------------------- 文档 -------------------------

	/** 文档分页：列表不返 content 大字段 */
	@GetMapping("/doc/page")
	public R<Page<HelpDoc>> docPage(@RequestParam(defaultValue = "1") long pageNum,
									@RequestParam(defaultValue = "10") long pageSize,
									@RequestParam(required = false) Long catalogId,
									@RequestParam(required = false) String title) {
		QueryWrapper query = QueryWrapper.create()
			.select("id", "catalog_id", "title", "view_count", "sort", "create_time", "update_time")
			.orderBy("sort", true).orderBy("id", false);
		if (catalogId != null) {
			query.eq("catalog_id", catalogId);
		}
		if (title != null && !title.isBlank()) {
			query.like("title", title);
		}
		return R.data(docMapper.paginate(pageNum, pageSize, query));
	}

	@GetMapping("/doc/detail")
	public R<HelpDoc> docDetail(@RequestParam Long id) {
		return R.data(docMapper.selectOneById(id));
	}

	@PostMapping("/doc/submit")
	@SaCheckPermission("sys:help:manage")
	public R<Void> docSubmit(@RequestBody HelpDoc doc) {
		if (doc.getId() == null) {
			// insertSelective 省略未传的 view_count，落库走 DB 默认 0
			docMapper.insertSelective(doc);
		} else {
			docMapper.update(doc);
		}
		return R.success("操作成功");
	}

	@PostMapping("/doc/remove")
	@SaCheckPermission("sys:help:manage")
	public R<Void> docRemove(@RequestBody List<Long> ids) {
		if (ids == null || ids.isEmpty()) {
			return R.success("删除成功");
		}
		docMapper.deleteBatchByIds(ids);
		// 级联清理这些文档的页面绑定
		bindingMapper.deleteByQuery(QueryWrapper.create().in("doc_id", ids));
		return R.success("删除成功");
	}

	// ------------------------- 页面绑定 -------------------------

	/** 某文档已绑定的页面 */
	@GetMapping("/binding/list")
	public R<List<HelpPageBinding>> bindingList(@RequestParam Long docId) {
		return R.data(bindingMapper.selectListByQuery(
			QueryWrapper.create().eq("doc_id", docId).orderBy("sort", true)));
	}

	@PostMapping("/binding/submit")
	@SaCheckPermission("sys:help:manage")
	public R<Void> bindingSubmit(@RequestBody HelpPageBinding binding) {
		if (binding.getRoutePath() == null || binding.getRoutePath().isBlank() || binding.getDocId() == null) {
			throw new ServiceException("页面路由与文档不能为空");
		}
		if (binding.getId() == null) {
			if (bindingMapper.selectCountByQuery(QueryWrapper.create()
				.eq("route_path", binding.getRoutePath()).eq("doc_id", binding.getDocId())) > 0) {
				throw new ServiceException("该页面已绑定此文档");
			}
			bindingMapper.insertSelective(binding);
		} else {
			bindingMapper.update(binding);
		}
		return R.success("操作成功");
	}

	@PostMapping("/binding/remove/{id}")
	@SaCheckPermission("sys:help:manage")
	public R<Void> bindingRemove(@PathVariable Long id) {
		bindingMapper.deleteById(id);
		return R.success("删除成功");
	}

	// ------------------------- 前台：按路由查阅 + 浏览量 -------------------------

	/** 当前页面（路由）关联的文档列表，按绑定排序 */
	@GetMapping("/page-docs")
	public R<List<HelpDoc>> pageDocs(@RequestParam String routePath) {
		List<HelpPageBinding> bindings = bindingMapper.selectListByQuery(
			QueryWrapper.create().eq("route_path", routePath).orderBy("sort", true));
		if (bindings.isEmpty()) {
			return R.data(List.of());
		}
		List<Long> docIds = bindings.stream().map(HelpPageBinding::getDocId).toList();
		Map<Long, HelpDoc> byId = docMapper
			.selectListByQuery(QueryWrapper.create().select("id", "title", "view_count").in("id", docIds))
			.stream().collect(Collectors.toMap(HelpDoc::getId, Function.identity()));
		// 按绑定顺序输出，跳过已删除文档
		List<HelpDoc> ordered = docIds.stream().map(byId::get).filter(java.util.Objects::nonNull).toList();
		return R.data(ordered);
	}

	/** 查看文档：浏览量原子 +1 并返回详情 */
	@PostMapping("/view/{id}")
	public R<HelpDoc> view(@PathVariable Long id) {
		Db.updateBySql("update help_doc set view_count = view_count + 1 where id = ? and is_deleted = 0", id);
		return R.data(docMapper.selectOneById(id));
	}
}
