package com.mugsun.boot.datasource;

import cn.dev33.satoken.annotation.SaCheckLogin;
import com.mugsun.boot.common.repeat.RepeatSubmit;
import com.mugsun.boot.datasource.entity.SysTenantDatasource;
import com.mugsun.boot.datasource.mapper.SysTenantDatasourceMapper;
import com.mugsun.core.tool.api.R;
import com.mugsun.core.tool.exception.ServiceException;
import com.mybatisflex.core.paginate.Page;
import com.mybatisflex.core.query.QueryWrapper;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;

/**
 * 租户独立数据源配置（平台级，超管操作）：CRUD + 保存即运行时注册/注销。
 */
@RestController
@RequestMapping("/system/tenant-datasource")
@SaCheckLogin
public class TenantDatasourceController {

	/** 密码脱敏占位符：读列表以此掩码，提交时以此判定"不改密码" */
	private static final String PASSWORD_MASK = "******";

	private final SysTenantDatasourceMapper datasourceMapper;
	private final TenantDataSourceRegistry registry;

	public TenantDatasourceController(SysTenantDatasourceMapper datasourceMapper, TenantDataSourceRegistry registry) {
		this.datasourceMapper = datasourceMapper;
		this.registry = registry;
	}

	@GetMapping("/page")
	public R<Page<SysTenantDatasource>> page(@RequestParam(defaultValue = "1") long pageNum,
											 @RequestParam(defaultValue = "10") long pageSize,
											 @RequestParam(required = false) String tenantCode) {
		QueryWrapper query = QueryWrapper.create().orderBy("id", false);
		if (tenantCode != null && !tenantCode.isBlank()) {
			query.like("tenant_code", tenantCode.trim());
		}
		Page<SysTenantDatasource> page = datasourceMapper.paginate(pageNum, pageSize, query);
		page.getRecords().forEach(c -> c.setDsPassword(PASSWORD_MASK));
		return R.data(page);
	}

	@RepeatSubmit
	@PostMapping("/submit")
	@org.springframework.transaction.annotation.Transactional(rollbackFor = Exception.class)
	public R<Void> submit(@RequestBody SysTenantDatasource param) {
		if (param == null) {
			throw new ServiceException("请填写租户编号、数据库地址和账号后再保存");
		}
		normalize(param);
		if (param.getId() == null) {
			if (param.getStatus() == null) {
				param.setStatus(1);
			}
			param.sanitizeForInsert();
			datasourceMapper.insertSelective(param);
		} else {
			SysTenantDatasource db = datasourceMapper.selectOneById(param.getId());
			if (db == null) {
				throw new ServiceException("这个数据源不存在。请刷新列表后再编辑");
			}
			// tenant_code 是注册键身份，禁改（改码旧键残留注册，两租户共用一库）；换码请先删后建
			if (!db.getTenantCode().equals(param.getTenantCode())) {
				throw new ServiceException("租户编号创建后不能修改。请删除这条配置，再用新编号新建");
			}
			// 密码占位符时不覆盖原密码
			if (PASSWORD_MASK.equals(param.getDsPassword())) {
				param.setDsPassword(db.getDsPassword());
			}
			param.sanitizeForUpdate();
			datasourceMapper.update(param);
		}
		// 保存即注册/注销：事务提交后再操作运行时池（注册失败不影响落库一致性，失败原因随异常上抛）
		SysTenantDatasource full = datasourceMapper.selectOneByQuery(
			QueryWrapper.create().eq("tenant_code", param.getTenantCode()));
		String tenantCode = full == null ? null : full.getTenantCode();
		boolean enabled = full != null && Integer.valueOf(1).equals(full.getStatus());
		com.mugsun.boot.common.tx.AfterCommit.execute(() -> {
			if (enabled) {
				registry.register(datasourceMapper.selectOneByQuery(
					QueryWrapper.create().eq("tenant_code", tenantCode)));
			} else if (tenantCode != null) {
				registry.unregister(tenantCode);
			}
		});
		return R.success(Integer.valueOf(1).equals(param.getStatus())
			? "已保存，并连上了这个数据库。该租户的业务数据会写到这里"
			: "已保存。当前是停用，业务数据仍写在主库。启用前请再核对地址和账号");
	}

	@RepeatSubmit
	@PostMapping("/remove")
	public R<Void> remove(@RequestBody List<Long> ids) {
		if (ids == null || ids.isEmpty()) {
			throw new ServiceException("请先选择要删除的数据源");
		}
		LinkedHashSet<Long> distinct = new LinkedHashSet<>();
		for (Long id : ids) {
			if (id == null) {
				throw new ServiceException("有数据源不存在。请刷新列表后再删除");
			}
			distinct.add(id);
		}
		List<Long> idList = new ArrayList<>(distinct);
		List<SysTenantDatasource> rows = datasourceMapper.selectListByIds(idList);
		if (rows.size() != idList.size()) {
			throw new ServiceException("有数据源不存在，或已经被删除。请刷新列表后再试");
		}
		for (SysTenantDatasource cfg : rows) {
			registry.unregister(cfg.getTenantCode());
		}
		datasourceMapper.deleteBatchByIds(idList);
		return R.success("已删除 " + rows.size() + " 个数据源。这些租户的业务数据会回到主库");
	}

	private void normalize(SysTenantDatasource param) {
		String code = param.getTenantCode() == null ? "" : param.getTenantCode().trim();
		if (code.isEmpty()) {
			throw new ServiceException("请填写租户编号");
		}
		if (!code.matches("^[0-9A-Za-z]{1,12}$")) {
			throw new ServiceException("租户编号请用字母或数字，最长 12 位");
		}
		param.setTenantCode(code);
		String url = param.getDsUrl() == null ? "" : param.getDsUrl().trim();
		if (url.isEmpty()) {
			throw new ServiceException("请填写数据库地址，例如 jdbc:postgresql://主机:5432/库名");
		}
		if (url.length() > 255) {
			throw new ServiceException("数据库地址请控制在 255 个字符以内");
		}
		if (!url.regionMatches(true, 0, "jdbc:", 0, 5)) {
			throw new ServiceException("数据库地址要以 jdbc: 开头，例如 jdbc:postgresql://主机:5432/库名");
		}
		param.setDsUrl(url);
		String username = param.getDsUsername() == null ? "" : param.getDsUsername().trim();
		if (username.isEmpty()) {
			throw new ServiceException("请填写数据库用户名");
		}
		if (username.length() > 64) {
			throw new ServiceException("数据库用户名请控制在 64 个字符以内");
		}
		param.setDsUsername(username);
		if (param.getId() == null && (param.getDsPassword() == null || param.getDsPassword().isBlank()
			|| PASSWORD_MASK.equals(param.getDsPassword()))) {
			throw new ServiceException("请填写数据库密码。编辑时如果不用改密码，保持掩码即可");
		}
		if (param.getDsPassword() != null && param.getDsPassword().length() > 128) {
			throw new ServiceException("数据库密码请控制在 128 个字符以内");
		}
		if (param.getIsolationType() == null) {
			param.setIsolationType(TenantDataSourceRegistry.ISOLATION_DATABASE);
		} else if (param.getIsolationType() != TenantDataSourceRegistry.ISOLATION_DATABASE
			&& param.getIsolationType() != TenantDataSourceRegistry.ISOLATION_SCHEMA) {
			throw new ServiceException("隔离方式只能选择独立库或独立 schema");
		}
		if (Integer.valueOf(TenantDataSourceRegistry.ISOLATION_SCHEMA).equals(param.getIsolationType())) {
			String schema = param.getSchemaName() == null ? "" : param.getSchemaName().trim();
			if (schema.isEmpty()) {
				throw new ServiceException("选择独立 schema 时，请填写 schema 名称");
			}
			if (schema.length() > 64 || !schema.matches("^[A-Za-z_][A-Za-z0-9_]*$")) {
				throw new ServiceException("schema 名称请用字母或下划线开头，只能包含字母、数字和下划线");
			}
			param.setSchemaName(schema);
		} else {
			param.setSchemaName(null);
		}
		if (param.getStatus() == null) {
			param.setStatus(1);
		} else if (param.getStatus() != 0 && param.getStatus() != 1) {
			throw new ServiceException("状态只能选择启用或停用");
		}
		String remark = param.getRemark() == null ? "" : param.getRemark().trim();
		if (remark.length() > 255) {
			throw new ServiceException("备注请控制在 255 个字以内");
		}
		param.setRemark(remark.isEmpty() ? null : remark);
		if (param.getId() == null) {
			long exists = datasourceMapper.selectCountByQuery(QueryWrapper.create().eq("tenant_code", code));
			if (exists > 0) {
				throw new ServiceException("租户「" + code + "」已经有数据源。请编辑原来那条，或先删除再新建");
			}
		}
	}
}
