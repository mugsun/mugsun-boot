package com.mugsun.boot.system.controller;

import cn.dev33.satoken.annotation.SaCheckLogin;
import cn.dev33.satoken.annotation.SaCheckPermission;
import cn.dev33.satoken.annotation.SaMode;
import com.mugsun.boot.common.repeat.RepeatSubmit;
import com.mugsun.boot.system.entity.SysRegion;
import com.mugsun.boot.system.excel.RegionExcel;
import com.mugsun.boot.system.mapper.SysRegionMapper;
import com.mugsun.core.tool.api.R;
import com.mugsun.core.tool.exception.ServiceException;
import com.mugsun.core.web.excel.ExcelUtil;
import com.mybatisflex.core.query.QueryWrapper;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 行政区划：按父级编码懒加载树，支持查看、修改、导入导出和空模板。
 */
@RestController
@RequestMapping("/system/region")
@SaCheckLogin
public class SysRegionController {

	private static final int MAX_LEVEL = 5;

	private final SysRegionMapper regionMapper;

	public SysRegionController(SysRegionMapper regionMapper) {
		this.regionMapper = regionMapper;
	}

	/** 懒加载：取某父级下的直接子节点，标注 leaf 供前端判定是否可展开 */
	@SaCheckPermission(value = {"sys:region:save", "sys:region:remove", "sys:region:import"}, mode = SaMode.OR)
	@GetMapping("/lazy-tree")
	public R<List<SysRegion>> lazyTree(@RequestParam(defaultValue = "0") String parentCode) {
		List<SysRegion> nodes = regionMapper.selectListByQuery(
			QueryWrapper.create().eq("parent_code", parentCode).orderBy("sort", true).orderBy("code", true));
		List<String> codes = nodes.stream().map(SysRegion::getCode).toList();
		Set<String> hasChildren = new HashSet<>();
		if (!codes.isEmpty()) {
			regionMapper.selectListByQuery(QueryWrapper.create().select("DISTINCT parent_code").in("parent_code", codes))
				.forEach(r -> hasChildren.add(r.getParentCode()));
		}
		nodes.forEach(n -> n.setLeaf(!hasChildren.contains(n.getCode())));
		return R.data(nodes);
	}

	@SaCheckPermission(value = {"sys:region:save", "sys:region:remove", "sys:region:import"}, mode = SaMode.OR)
	@GetMapping("/detail")
	public R<SysRegion> detail(@RequestParam(required = false) Long id) {
		if (id == null) {
			throw new ServiceException("请从列表里选择一条区划后再查看");
		}
		SysRegion region = regionMapper.selectOneById(id);
		if (region == null) {
			throw new ServiceException("这条区划不存在，或已被删除。请刷新列表后再看");
		}
		return R.data(region);
	}

	@SaCheckPermission("sys:region:save")
	@PostMapping("/submit")
	@RepeatSubmit(message = "请勿重复保存。请到列表里确认这级区划是否已经提交")
	public R<Void> submit(@RequestBody SysRegion region) {
		if (region == null) {
			throw new ServiceException("请填写区划名称和编码后再保存");
		}
		String name = requireName(region.getName());
		String code = requireCode(region.getCode());
		String parentCode = blankToRoot(region.getParentCode());
		int sort = requireSort(region.getSort());
		if (region.getId() == null) {
			if (findByCode(code) != null) {
				throw new ServiceException("区划编码已存在。请换一个编码，或在列表里编辑原来那条");
			}
			int level = levelForNew(parentCode, code);
			SysRegion row = new SysRegion();
			row.setName(name);
			row.setCode(code);
			row.setParentCode(parentCode);
			row.setLevel(level);
			row.setSort(sort);
			regionMapper.insertSelective(row);
			return R.success("已保存。可以在列表里展开上级查看");
		}
		SysRegion exist = regionMapper.selectOneById(region.getId());
		if (exist == null) {
			throw new ServiceException("这条区划不存在，或已被删除。请刷新列表后再编辑");
		}
		if (!code.equals(exist.getCode())) {
			throw new ServiceException("区划编码不能修改。需要新编码时，请删除后重新新增");
		}
		if (!parentCode.equals(blankToRoot(exist.getParentCode()))) {
			throw new ServiceException("上级不能修改。请在正确的上级下新增，或先删除再重建");
		}
		exist.setName(name);
		exist.setSort(sort);
		regionMapper.update(exist);
		return R.success("已保存。列表里的名称已更新");
	}

	@SaCheckPermission("sys:region:remove")
	@PostMapping("/remove/{id}")
	@RepeatSubmit(message = "请勿重复删除。请刷新列表确认这级区划是否还在")
	public R<Void> remove(@PathVariable Long id) {
		if (id == null) {
			throw new ServiceException("请从列表里选择要删除的区划");
		}
		SysRegion region = regionMapper.selectOneById(id);
		if (region == null) {
			throw new ServiceException("这条区划不存在，或已被删除。请刷新列表后再试");
		}
		long childCount = regionMapper.selectCountByQuery(
			QueryWrapper.create().eq("parent_code", region.getCode()));
		if (childCount > 0) {
			throw new ServiceException("下面还有下级区划。请先删除下级，再删除这一级");
		}
		regionMapper.deleteById(id);
		return R.success("已删除。列表里不会再出现这一级");
	}

	/** 导出全部区划 */
	@SaCheckPermission(value = {"sys:region:save", "sys:region:remove", "sys:region:import"}, mode = SaMode.OR)
	@GetMapping("/export")
	public void export(HttpServletResponse response) {
		List<RegionExcel> rows = regionMapper.selectListByQuery(QueryWrapper.create().orderBy("code", true))
			.stream().map(this::toExcel).toList();
		ExcelUtil.export(response, "行政区划", "行政区划", rows, RegionExcel.class);
	}

	/** 空模板：表头与导入一致，方便按行填写 */
	@SaCheckPermission("sys:region:import")
	@GetMapping("/template")
	public void template(HttpServletResponse response) {
		ExcelUtil.export(response, "行政区划模板", "行政区划", List.of(), RegionExcel.class);
	}

	/** 导入区划：先整表校验，通过后再按编码新增或更新 */
	@SaCheckPermission("sys:region:import")
	@PostMapping("/import")
	@RepeatSubmit(message = "请勿重复导入。请先看列表里是否已经出现这些区划")
	public R<Integer> importData(@RequestParam(value = "file", required = false) MultipartFile file) {
		if (file == null) {
			throw new ServiceException("请先选择填好的表格。可以点下载模板，按表头填写后再导入");
		}
		List<RegionExcel> rows = com.mugsun.boot.common.excel.ImportFiles.requireRows(
			com.mugsun.boot.common.excel.ImportFiles.readExcel(file, RegionExcel.class),
			"这份表格里没有区划。请先下载模板，填好编码、父级编码、名称和层级后再导入");
		List<PreparedRegion> prepared = prepareImport(rows);
		int count = 0;
		for (PreparedRegion row : prepared) {
			SysRegion exist = findByCode(row.code());
			if (exist != null) {
				exist.setName(row.name());
				exist.setParentCode(row.parentCode());
				exist.setLevel(row.level());
				regionMapper.update(exist);
			} else {
				SysRegion region = new SysRegion();
				region.setCode(row.code());
				region.setParentCode(row.parentCode());
				region.setName(row.name());
				region.setLevel(row.level());
				region.setSort(0);
				regionMapper.insertSelective(region);
			}
			count++;
		}
		R<Integer> result = R.data(count);
		result.setMsg("已导入 " + count + " 条。可以在列表里按上级展开查看");
		return result;
	}

	private List<PreparedRegion> prepareImport(List<RegionExcel> rows) {
		Map<String, Integer> known = new HashMap<>();
		for (SysRegion region : regionMapper.selectListByQuery(QueryWrapper.create())) {
			if (region.getCode() != null) {
				known.put(region.getCode(), region.getLevel() == null ? 1 : region.getLevel());
			}
		}
		Set<String> seen = new HashSet<>();
		List<PreparedRegion> prepared = new ArrayList<>();
		List<ImportLine> ordered = new ArrayList<>();
		for (int i = 0; i < rows.size(); i++) {
			ordered.add(new ImportLine(i + 2, rows.get(i)));
		}
		ordered.sort((a, b) -> Integer.compare(levelOf(a.row()), levelOf(b.row())));
		for (ImportLine lineRow : ordered) {
			RegionExcel row = lineRow.row();
			int line = lineRow.line();
			String code = row.getCode() == null ? "" : row.getCode().trim();
			if (code.isEmpty()) {
				throw new ServiceException("第 " + line + " 行没有区划编码。请补上编码，或删掉空行后再导入");
			}
			if (!code.matches("^[0-9]{2,12}$")) {
				throw new ServiceException("第 " + line + " 行的区划编码请填写 2 到 12 位数字");
			}
			if (!seen.add(code)) {
				throw new ServiceException("第 " + line + " 行的区划编码和前面重复了。请删掉重复行后再导入");
			}
			String name = row.getName() == null ? "" : row.getName().trim();
			if (name.isEmpty()) {
				throw new ServiceException("第 " + line + " 行没有名称。请填上名称后再导入");
			}
			if (name.length() > 64) {
				throw new ServiceException("第 " + line + " 行的名称请控制在 64 字以内");
			}
			String parentCode = blankToRoot(row.getParentCode());
			if (code.equals(parentCode)) {
				throw new ServiceException("第 " + line + " 行的上级不能是自己。请改成上一级的编码，顶级填 0");
			}
			Integer level = row.getLevel();
			int expect = expectedLevel(parentCode, known, line);
			if (level == null) {
				level = expect;
			} else if (level != expect) {
				throw new ServiceException("第 " + line + " 行的层级和上级对不上。顶级填 1，每往下一级加 1");
			}
			prepared.add(new PreparedRegion(code, parentCode, name, level));
			known.put(code, level);
		}
		return prepared;
	}

	private static int levelOf(RegionExcel row) {
		return row.getLevel() == null ? 99 : row.getLevel();
	}

	private int expectedLevel(String parentCode, Map<String, Integer> known, int line) {
		if ("0".equals(parentCode)) {
			return 1;
		}
		Integer parentLevel = known.get(parentCode);
		if (parentLevel == null) {
			throw new ServiceException("第 " + line + " 行的上级编码不存在。请先写上上级那一行，或改成已有的上级编码");
		}
		if (parentLevel >= MAX_LEVEL) {
			throw new ServiceException("第 " + line + " 行的上级已经是最末级，不能再挂下级");
		}
		return parentLevel + 1;
	}

	private int levelForNew(String parentCode, String code) {
		if (parentCode.equals(code)) {
			throw new ServiceException("上级不能是自己。请改成上一级的编码，顶级不用填");
		}
		if ("0".equals(parentCode)) {
			return 1;
		}
		SysRegion parent = findByCode(parentCode);
		if (parent == null) {
			throw new ServiceException("上级区划不存在。请先新增上级，或选一个已有的上级");
		}
		int parentLevel = parent.getLevel() == null ? 1 : parent.getLevel();
		if (parentLevel >= MAX_LEVEL) {
			throw new ServiceException("这一级已经是最末级，不能再新增下级");
		}
		return parentLevel + 1;
	}

	private SysRegion findByCode(String code) {
		return regionMapper.selectOneByQuery(QueryWrapper.create().eq("code", code));
	}

	private static String requireName(String raw) {
		if (raw == null || raw.isBlank()) {
			throw new ServiceException("请填写区划名称");
		}
		String name = raw.trim();
		if (name.length() > 64) {
			throw new ServiceException("区划名称请控制在 64 字以内");
		}
		return name;
	}

	private static String requireCode(String raw) {
		if (raw == null || raw.isBlank()) {
			throw new ServiceException("请填写区划编码。用 2 到 12 位数字，例如省级 11、地市 1101");
		}
		String code = raw.trim();
		if (!code.matches("^[0-9]{2,12}$")) {
			throw new ServiceException("区划编码请填写 2 到 12 位数字，例如省级 11、地市 1101");
		}
		return code;
	}

	private static int requireSort(Integer sort) {
		if (sort == null) {
			return 0;
		}
		if (sort < 0 || sort > 9999) {
			throw new ServiceException("排序请填写 0 到 9999 的整数");
		}
		return sort;
	}

	private static String blankToRoot(String parentCode) {
		if (parentCode == null || parentCode.isBlank()) {
			return "0";
		}
		return parentCode.trim();
	}

	private RegionExcel toExcel(SysRegion region) {
		RegionExcel row = new RegionExcel();
		row.setCode(region.getCode());
		row.setParentCode(region.getParentCode());
		row.setName(region.getName());
		row.setLevel(region.getLevel());
		return row;
	}

	private record PreparedRegion(String code, String parentCode, String name, int level) {
	}

	private record ImportLine(int line, RegionExcel row) {
	}
}
