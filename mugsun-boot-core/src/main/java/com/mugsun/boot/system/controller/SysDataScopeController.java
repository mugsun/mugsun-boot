package com.mugsun.boot.system.controller;

import cn.dev33.satoken.annotation.SaCheckLogin;
import cn.dev33.satoken.annotation.SaCheckPermission;
import com.mugsun.boot.config.DataScopeRuleLoader;
import com.mugsun.boot.datascope.DataScopeRuleIndex;
import com.mugsun.boot.log.OperationLog;
import com.mugsun.boot.system.entity.SysDataScope;
import com.mugsun.boot.system.entity.SysMenu;
import com.mugsun.boot.system.mapper.SysDataScopeMapper;
import com.mugsun.boot.system.mapper.SysMenuMapper;
import com.mugsun.boot.tenant.TenantContext;
import com.mugsun.core.tool.api.R;
import com.mugsun.core.tool.exception.ServiceException;
import com.mybatisflex.core.paginate.Page;
import com.mybatisflex.core.query.QueryWrapper;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * 数据权限规则：维护受控表和收紧范围。启用后立刻作用到对应表的查询。
 */
@RestController
@RequestMapping("/system/data-scope")
@SaCheckLogin
public class SysDataScopeController {

	private static final Pattern CODE = Pattern.compile("^[A-Za-z0-9_:]{1,64}$");
	private static final Pattern IDENT = Pattern.compile("^[a-z][a-z0-9_]{0,63}$");

	private final SysDataScopeMapper dataScopeMapper;
	private final SysMenuMapper menuMapper;
	private final DataScopeRuleLoader ruleLoader;

	public SysDataScopeController(SysDataScopeMapper dataScopeMapper, SysMenuMapper menuMapper,
								  DataScopeRuleLoader ruleLoader) {
		this.dataScopeMapper = dataScopeMapper;
		this.menuMapper = menuMapper;
		this.ruleLoader = ruleLoader;
	}

	@GetMapping("/page")
	@SaCheckPermission("sys:data-scope:list")
	public R<Page<SysDataScope>> page(@RequestParam(defaultValue = "1") long pageNum,
									  @RequestParam(defaultValue = "10") long pageSize,
									  @RequestParam(required = false) String name) {
		assertPlatform();
		QueryWrapper query = QueryWrapper.create().orderBy("id", true);
		if (name != null && !name.isBlank()) {
			query.like("scope_name", name.trim());
		}
		return R.data(dataScopeMapper.paginate(pageNum, pageSize, query));
	}

	@GetMapping("/effective")
	@SaCheckPermission("sys:data-scope:list")
	public R<List<Map<String, Object>>> effective() {
		assertPlatform();
		List<Map<String, Object>> rows = DataScopeRuleIndex.snapshot().stream().map(hit -> {
			Map<String, Object> row = new LinkedHashMap<>();
			row.put("table", hit.table());
			row.put("deptColumn", hit.deptColumn());
			row.put("userColumn", hit.userColumn());
			row.put("scopeType", hit.scopeType());
			return row;
		}).toList();
		return R.data(rows);
	}

	@PostMapping("/submit")
	@SaCheckPermission("sys:data-scope:save")
	@OperationLog("保存数据权限规则")
	@Transactional(rollbackFor = Exception.class)
	public R<Void> submit(@RequestBody SysDataScope body) {
		assertPlatform();
		String name = body.getScopeName() == null ? "" : body.getScopeName().trim();
		String code = body.getResourceCode() == null ? "" : body.getResourceCode().trim();
		String table = body.getTableName() == null ? "" : body.getTableName().trim().toLowerCase();
		String dept = body.getDeptColumn() == null ? "" : body.getDeptColumn().trim().toLowerCase();
		String user = body.getUserColumn() == null ? "" : body.getUserColumn().trim().toLowerCase();
		if (name.isEmpty()) {
			throw new ServiceException("请填写规则名称");
		}
		if (name.length() > 64) {
			throw new ServiceException("规则名称请控制在 64 字以内");
		}
		if (code.isEmpty()) {
			throw new ServiceException("请填写规则编码");
		}
		if (!CODE.matcher(code).matches()) {
			throw new ServiceException("编码请使用字母、数字、下划线或冒号，最长 64 位");
		}
		if (!IDENT.matcher(table).matches() || !IDENT.matcher(dept).matches() || !IDENT.matcher(user).matches()) {
			throw new ServiceException("表名和列名请使用小写字母、数字或下划线，并以字母开头");
		}
		if (dept.equals(user)) {
			throw new ServiceException("部门列和本人列不能相同，否则无法区分部门范围和本人范围");
		}
		int scopeType = body.getScopeType() == null ? 1 : body.getScopeType();
		if (scopeType < 1 || scopeType > 4) {
			throw new ServiceException("自定义部门或自定义 SQL 请在角色上配置。规则请选择全部、本部门、本部门及子部门或仅本人");
		}
		int status = body.getStatus() == null ? 1 : body.getStatus();
		if (status != 0 && status != 1) {
			throw new ServiceException("状态只能是启用或停用");
		}
		String remark = body.getRemark() == null ? null : body.getRemark().trim();
		if (remark != null && remark.length() > 255) {
			throw new ServiceException("备注请控制在 255 字以内");
		}
		if (body.getMenuId() != null) {
			SysMenu menu = menuMapper.selectOneById(body.getMenuId());
			if (menu == null) {
				throw new ServiceException("关联菜单不存在或已删除，请重新选择");
			}
		}
		assertCodeFree(code, body.getId());
		if (status == 1) {
			assertTableFree(table, body.getId());
		}
		if (body.getId() == null) {
			SysDataScope row = new SysDataScope();
			fill(row, name, code, table, dept, user, scopeType, status, remark, body.getMenuId());
			dataScopeMapper.insert(row);
		} else {
			SysDataScope row = require(body.getId());
			fill(row, name, code, table, dept, user, scopeType, status, remark, body.getMenuId());
			dataScopeMapper.update(row);
		}
		ruleLoader.reload();
		if (status == 1 && scopeType != 1) {
			return R.success("已启用。角色范围比本规则更宽时，" + table + " 的查询会按本规则收紧");
		}
		if (status == 1) {
			return R.success("已启用。" + table + " 会按角色的数据范围过滤");
		}
		return R.success("已保存为停用。这张表不再按本规则收紧");
	}

	@PostMapping("/remove")
	@SaCheckPermission("sys:data-scope:remove")
	@OperationLog("删除数据权限规则")
	@Transactional(rollbackFor = Exception.class)
	public R<Void> remove(@RequestBody List<Long> ids) {
		assertPlatform();
		if (ids == null || ids.isEmpty()) {
			throw new ServiceException("请选择要删除的规则");
		}
		for (Long id : ids) {
			if (id == null) {
				continue;
			}
			SysDataScope row = dataScopeMapper.selectOneById(id);
			if (row == null) {
				throw new ServiceException("规则不存在或已删除，请刷新后再试");
			}
			dataScopeMapper.deleteById(id);
		}
		ruleLoader.reload();
		return R.success("已删除。对应表已恢复为角色上的数据范围");
	}

	private void fill(SysDataScope row, String name, String code, String table, String dept, String user,
					  int scopeType, int status, String remark, Long menuId) {
		row.setScopeName(name);
		row.setResourceCode(code);
		row.setTableName(table);
		row.setDeptColumn(dept);
		row.setUserColumn(user);
		row.setScopeType(scopeType);
		row.setStatus(status);
		row.setRemark(remark == null || remark.isEmpty() ? null : remark);
		row.setMenuId(menuId);
	}

	private void assertCodeFree(String code, Long id) {
		SysDataScope exist = dataScopeMapper.selectOneByQuery(QueryWrapper.create().eq("resource_code", code));
		if (exist != null && (id == null || !exist.getId().equals(id))) {
			throw new ServiceException("规则编码已存在，请换一个");
		}
	}

	private void assertTableFree(String table, Long id) {
		SysDataScope exist = dataScopeMapper.selectOneByQuery(
			QueryWrapper.create().eq("table_name", table).eq("status", 1));
		if (exist != null && (id == null || !exist.getId().equals(id))) {
			throw new ServiceException("表 " + table + " 已有启用规则「" + exist.getScopeName() + "」。请先停用它，或把本规则设为停用后再保存");
		}
	}

	private SysDataScope require(Long id) {
		SysDataScope row = dataScopeMapper.selectOneById(id);
		if (row == null) {
			throw new ServiceException("规则不存在或已删除，请刷新后再试");
		}
		return row;
	}

	private void assertPlatform() {
		if (!TenantContext.isPlatformSuperAdmin()) {
			throw new ServiceException("数据权限规则只允许平台管理员维护。请使用平台管理员账号操作");
		}
	}
}
