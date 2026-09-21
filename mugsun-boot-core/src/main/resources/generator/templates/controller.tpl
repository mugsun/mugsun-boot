package #(controllerPkg);

import cn.dev33.satoken.annotation.SaCheckLogin;
import cn.dev33.satoken.annotation.SaCheckPermission;
import #(entityPkg).#(entityName);
import #(mapperPkg).#(mapperName);
import com.mugsun.core.tool.api.R;
import com.mybatisflex.core.paginate.Page;
import com.mybatisflex.core.query.QueryWrapper;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * #(functionName)管理
 *
 * @author #(author)
 */
@RestController
@RequestMapping("#(path)")
@SaCheckLogin
public class #(entityName)Controller {

	private final #(mapperName) #(mapperVar);

	public #(entityName)Controller(#(mapperName) #(mapperVar)) {
		this.#(mapperVar) = #(mapperVar);
	}
#if(isTree)

	/** 懒加载树：parentId 空取根节点（父列=0），否则取其直接子节点；分组统计 hasChildren 驱动前端展开箭头（防大树全量加载） */
	@GetMapping("/tree")
	public R<List<#(entityName)>> tree(@RequestParam(required = false) Long parentId) {
		List<#(entityName)> list = #(mapperVar).selectListByQuery(QueryWrapper.create()
			.eq("#(parentColumn)", parentId == null ? 0L : parentId).orderBy("id", true));
		if (!list.isEmpty()) {
			java.util.Map<Long, Long> childCounts = new java.util.HashMap<>();
			for (com.mybatisflex.core.row.Row row : com.mybatisflex.core.row.Db.selectListBySql(
				"SELECT #(parentColumn) AS pid, COUNT(*) AS cnt FROM #(tableName) WHERE is_deleted = 0 GROUP BY #(parentColumn)")) {
				childCounts.put(row.getLong("pid"), row.getLong("cnt"));
			}
			list.forEach(n -> n.setHasChildren(childCounts.getOrDefault(n.getId(), 0L) > 0));
		}
		return R.data(list);
	}
#end

	@GetMapping("/page")
	public R<Page<#(entityName)>> page(@RequestParam(defaultValue = "1") long pageNum,
			@RequestParam(defaultValue = "10") long pageSize) {
		return R.data(#(mapperVar).paginate(pageNum, pageSize, QueryWrapper.create().orderBy("id", false)));
	}

	@GetMapping("/detail")
	public R<#(entityName)> detail(@RequestParam Long id) {
		return R.data(#(mapperVar).selectOneById(id));
	}

	@SaCheckPermission("#(permPrefix):save")
	@PostMapping("/submit")
	public R<Void> submit(@RequestBody #(entityName) #(entityVar)) {
		if (#(entityVar).getId() == null) {
			#(mapperVar).insertSelective(#(entityVar));
		} else {
			#(mapperVar).update(#(entityVar));
		}
		return R.success("操作成功");
	}

	@SaCheckPermission("#(permPrefix):remove")
	@PostMapping("/remove")
	public R<Void> remove(@RequestBody List<Long> ids) {
		#(mapperVar).deleteBatchByIds(ids);
		return R.success("删除成功");
	}
}
