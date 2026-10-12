package com.mugsun.boot.gen;

import com.mugsun.boot.gen.entity.GenScheme;
import com.mugsun.boot.gen.mapper.GenSchemeMapper;
import com.mugsun.boot.system.service.MenuService;
import com.mugsun.boot.tenant.TenantContext;
import com.mugsun.core.tool.exception.ServiceException;
import com.mybatisflex.core.paginate.Page;
import com.mybatisflex.core.query.QueryWrapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * 生成方案。同一租户只启用一套，导入时补上没填写的模块、包名、前缀和作者。
 */
@Service
public class GenSchemeService {

	private static final Pattern CODE = Pattern.compile("^[a-z][a-z0-9_]{0,39}$");
	private static final Pattern MODULE = Pattern.compile("^[a-z][a-z0-9]{0,31}$");
	private static final Pattern PKG = Pattern.compile("^[a-z][a-z0-9]*(\\.[a-z][a-z0-9]*)+$");
	private static final Pattern PREFIX = Pattern.compile("^[a-z][a-z0-9_]{0,19}$");

	private final GenSchemeMapper schemeMapper;
	private final MenuService menuService;

	public GenSchemeService(GenSchemeMapper schemeMapper, MenuService menuService) {
		this.schemeMapper = schemeMapper;
		this.menuService = menuService;
	}

	public Page<GenScheme> page(long pageNum, long pageSize, String name) {
		QueryWrapper query = QueryWrapper.create().orderBy("status", false).orderBy("id", false);
		if (name != null && !name.isBlank()) {
			query.like("scheme_name", name.trim());
		}
		return schemeMapper.paginate(pageNum, Math.min(pageSize, 100), query);
	}

	public GenScheme enabledOrNull() {
		return schemeMapper.selectOneByQuery(QueryWrapper.create().eq("status", 1));
	}

	public GenScheme require(Long id) {
		if (id == null) {
			throw new ServiceException("请从列表中选择一套生成方案后再操作");
		}
		GenScheme row = schemeMapper.selectOneById(id);
		if (row == null) {
			throw new ServiceException("这套生成方案不存在，或属于其他租户。请刷新列表后再试");
		}
		return row;
	}

	public Map<String, Object> save(Long id, String name, String code, String moduleName, String basePackage,
									 String tablePrefix, String author, Long parentMenuId, String remark) {
		String schemeName = requireText(name, "请填写方案名称", 64, "方案名称请控制在 64 字以内");
		String schemeCode = normalizeCode(code);
		String module = normalizeModule(moduleName);
		String pkg = normalizePackage(basePackage);
		String prefix = normalizePrefix(tablePrefix);
		String who = requireText(author, "请填写作者，导入表时会写进生成代码", 32, "作者请控制在 32 字以内");
		String note = remark == null ? "" : remark.trim();
		if (note.length() > 200) {
			throw new ServiceException("备注请控制在 200 字以内");
		}
		Long menuId = normalizeMenu(parentMenuId);
		QueryWrapper dup = QueryWrapper.create().eq("scheme_code", schemeCode);
		if (id != null) {
			dup.ne("id", id);
		}
		if (schemeMapper.selectCountByQuery(dup) > 0) {
			throw new ServiceException("方案编码已存在，请换一个");
		}
		GenScheme row;
		if (id == null) {
			row = new GenScheme();
			row.setTenantId(TenantContext.current());
			row.setStatus(0);
			fill(row, schemeName, schemeCode, module, pkg, prefix, who, menuId, note);
			row.sanitizeForInsert();
			schemeMapper.insert(row);
		} else {
			row = require(id);
			fill(row, schemeName, schemeCode, module, pkg, prefix, who, menuId, note);
			row.sanitizeForUpdate();
			schemeMapper.update(row);
		}
		Map<String, Object> body = new LinkedHashMap<>();
		body.put("id", row.getId());
		body.put("enabled", Integer.valueOf(1).equals(row.getStatus()));
		return body;
	}

	@Transactional(rollbackFor = Exception.class)
	public Map<String, Object> enable(Long id) {
		GenScheme row = require(id);
		if (Integer.valueOf(1).equals(row.getStatus())) {
			throw new ServiceException("这个方案已经在用。导入表时会带上它的模块、包名和作者");
		}
		List<GenScheme> enabled = schemeMapper.selectListByQuery(QueryWrapper.create().eq("status", 1));
		for (GenScheme other : enabled) {
			other.setStatus(0);
			other.sanitizeForUpdate();
			schemeMapper.update(other);
		}
		row.setStatus(1);
		row.sanitizeForUpdate();
		schemeMapper.update(row);
		Map<String, Object> body = new LinkedHashMap<>();
		body.put("id", row.getId());
		body.put("schemeName", row.getSchemeName());
		return body;
	}

	@Transactional(rollbackFor = Exception.class)
	public List<GenScheme> remove(List<Long> ids) {
		if (ids == null || ids.isEmpty()) {
			throw new ServiceException("请先勾选要删除的生成方案");
		}
		List<GenScheme> rows = new ArrayList<>();
		for (Long id : ids) {
			GenScheme row = schemeMapper.selectOneById(id);
			if (row == null) {
				throw new ServiceException("这套生成方案不存在，或属于其他租户。请刷新列表后再试");
			}
			rows.add(row);
		}
		for (GenScheme row : rows) {
			schemeMapper.deleteById(row.getId());
		}
		return rows;
	}

	public static Map<String, Object> view(GenScheme row) {
		Map<String, Object> map = new LinkedHashMap<>();
		map.put("id", row.getId());
		map.put("schemeName", row.getSchemeName());
		map.put("schemeCode", row.getSchemeCode());
		map.put("moduleName", row.getModuleName());
		map.put("basePackage", row.getBasePackage());
		map.put("tablePrefix", row.getTablePrefix());
		map.put("author", row.getAuthor());
		map.put("parentMenuId", row.getParentMenuId());
		map.put("remark", row.getRemark());
		map.put("status", row.getStatus());
		map.put("createTime", row.getCreateTime());
		return map;
	}

	private static void fill(GenScheme row, String name, String code, String module, String pkg, String prefix,
							 String author, Long menuId, String remark) {
		row.setSchemeName(name);
		row.setSchemeCode(code);
		row.setModuleName(module);
		row.setBasePackage(pkg);
		row.setTablePrefix(prefix);
		row.setAuthor(author);
		row.setParentMenuId(menuId);
		row.setRemark(remark);
	}

	private Long normalizeMenu(Long parentMenuId) {
		if (parentMenuId == null) {
			return null;
		}
		if (!menuService.exists(parentMenuId)) {
			throw new ServiceException("上级菜单不存在，或属于其他租户。请从菜单管理复制编号，或留空");
		}
		return parentMenuId;
	}

	private static String normalizeCode(String raw) {
		String code = requireText(raw, "请填写方案编码，导入时用来区分这套配置", 40, "方案编码请控制在 40 字以内");
		if (!CODE.matcher(code).matches()) {
			throw new ServiceException("方案编码请使用小写字母开头，只能包含小写字母、数字或下划线");
		}
		return code;
	}

	private static String normalizeModule(String raw) {
		String module = requireText(raw, "请填写模块名，例如 system", 32, "模块名请控制在 32 字以内");
		if (!MODULE.matcher(module).matches()) {
			throw new ServiceException("模块名请使用小写字母开头，只能包含小写字母或数字");
		}
		return module;
	}

	private static String normalizePackage(String raw) {
		String pkg = requireText(raw, "请填写基础包，例如 com.example.demo", 120, "基础包请控制在 120 字以内");
		if (!PKG.matcher(pkg).matches()) {
			throw new ServiceException("基础包请使用小写字母和点，例如 com.example.demo");
		}
		return pkg;
	}

	private static String normalizePrefix(String raw) {
		if (raw == null || raw.isBlank()) {
			return "";
		}
		String prefix = raw.trim();
		if (prefix.length() > 20) {
			throw new ServiceException("表前缀请控制在 20 字以内");
		}
		if (!PREFIX.matcher(prefix).matches()) {
			throw new ServiceException("表前缀请使用小写字母开头，只能包含小写字母、数字或下划线");
		}
		return prefix;
	}

	private static String requireText(String raw, String empty, int max, String tooLong) {
		if (raw == null || raw.isBlank()) {
			throw new ServiceException(empty);
		}
		String text = raw.trim();
		if (text.length() > max) {
			throw new ServiceException(tooLong);
		}
		return text;
	}
}
