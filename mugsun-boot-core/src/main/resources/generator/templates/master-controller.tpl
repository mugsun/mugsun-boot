package #(controllerPkg);

import cn.dev33.satoken.annotation.SaCheckLogin;
import cn.dev33.satoken.annotation.SaCheckPermission;
import #(entityPkg).#(entityName);
import #(mapperPkg).#(mapperName);
import #(servicePkg).#(entityName)Service;
import com.mugsun.core.tool.api.R;
import com.mybatisflex.core.paginate.Page;
import com.mybatisflex.core.query.QueryWrapper;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * #(functionName)管理（主子表一对多）
 *
 * @author #(author)
 */
@RestController
@RequestMapping("#(path)")
@SaCheckLogin
public class #(entityName)Controller {

	private final #(mapperName) #(mapperVar);
	private final #(entityName)Service #(entityVar)Service;

	public #(entityName)Controller(#(mapperName) #(mapperVar), #(entityName)Service #(entityVar)Service) {
		this.#(mapperVar) = #(mapperVar);
		this.#(entityVar)Service = #(entityVar)Service;
	}

	@GetMapping("/page")
	public R<Page<#(entityName)>> page(@RequestParam(defaultValue = "1") long pageNum,
			@RequestParam(defaultValue = "10") long pageSize) {
		return R.data(#(mapperVar).paginate(pageNum, pageSize, QueryWrapper.create().orderBy("id", false)));
	}

	@GetMapping("/detail")
	public R<#(entityName)> detail(@RequestParam Long id) {
		return R.data(#(entityVar)Service.detail(id));
	}

	@SaCheckPermission("#(permPrefix):save")
	@PostMapping("/submit")
	public R<Void> submit(@RequestBody #(entityName) #(entityVar)) {
		#(entityVar)Service.save(#(entityVar));
		return R.success("操作成功");
	}

	@SaCheckPermission("#(permPrefix):remove")
	@PostMapping("/remove")
	public R<Void> remove(@RequestBody List<Long> ids) {
		#(entityVar)Service.remove(ids);
		return R.success("删除成功");
	}
}
