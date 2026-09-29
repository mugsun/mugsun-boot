package com.mugsun.boot.ai.service;

import com.mugsun.boot.ai.AiConstants;
import com.mugsun.boot.ai.AiModuleService;
import com.mugsun.boot.ai.entity.AiDatasource;
import com.mugsun.boot.ai.mapper.AiDatasourceMapper;
import com.mugsun.boot.tenant.TenantContext;
import com.mugsun.core.tool.exception.ServiceException;
import com.mybatisflex.core.paginate.Page;
import com.mybatisflex.core.query.QueryWrapper;
import org.springframework.stereotype.Service;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Service
public class AiDatasourceBizService {

	private final AiModuleService moduleService;
	private final AiDatasourceMapper mapper;

	public AiDatasourceBizService(AiModuleService moduleService, AiDatasourceMapper mapper) {
		this.moduleService = moduleService;
		this.mapper = mapper;
	}

	public Page<AiDatasource> page(long pageNum, long pageSize, String name) {
		moduleService.requireEnabled();
		QueryWrapper q = QueryWrapper.create().orderBy("id", false);
		if (name != null && !name.isBlank()) {
			q.like("name", name.trim());
		}
		Page<AiDatasource> page = mapper.paginate(pageNum, pageSize, q);
		page.getRecords().forEach(this::mask);
		return page;
	}

	public AiDatasource detail(Long id) {
		moduleService.requireEnabled();
		AiDatasource d = require(id);
		mask(d);
		return d;
	}

	public AiDatasource submit(AiDatasource body) {
		moduleService.requireEnabled();
		if (body.getName() == null || body.getName().isBlank()) {
			throw new ServiceException("请填写名称");
		}
		fillDriver(body);
		if (body.getId() == null) {
			body.sanitizeForInsert();
			body.setTenantId(TenantContext.current());
			if (body.getReadOnlyFlag() == null) {
				body.setReadOnlyFlag(AiConstants.FLAG_YES);
			}
			mapper.insert(body);
		} else {
			AiDatasource db = require(body.getId());
			String keep = db.getPassword();
			body.sanitizeForUpdate();
			if (body.getPassword() == null || body.getPassword().isBlank()) {
				body.setPassword(keep);
			}
			body.setTenantId(db.getTenantId());
			mapper.update(body);
		}
		mask(body);
		return body;
	}

	public void remove(List<Long> ids) {
		moduleService.requireEnabled();
		for (Long id : ids) {
			require(id);
			mapper.deleteById(id);
		}
	}

	public Map<String, Object> test(Long id) {
		moduleService.requireEnabled();
		AiDatasource d = require(id);
		try (Connection c = open(d)) {
			return Map.of("ok", c.isValid(5));
		} catch (Exception e) {
			return Map.of("ok", false, "message", e.getMessage() == null ? "连接失败" : e.getMessage());
		}
	}

	public List<String> tables(Long id) {
		moduleService.requireEnabled();
		AiDatasource d = require(id);
		List<String> names = new ArrayList<>();
		try (Connection c = open(d)) {
			DatabaseMetaData meta = c.getMetaData();
			try (ResultSet rs = meta.getTables(c.getCatalog(), null, "%", new String[]{"TABLE", "VIEW"})) {
				while (rs.next()) {
					names.add(rs.getString("TABLE_NAME"));
					if (names.size() >= 500) {
						break;
					}
				}
			}
		} catch (Exception e) {
			throw new ServiceException("列举表失败: " + e.getMessage());
		}
		return names;
	}

	public AiDatasource requireRaw(Long id) {
		return require(id);
	}

	public Connection open(AiDatasource d) throws Exception {
		if (d.getDriverClass() != null && !d.getDriverClass().isBlank()) {
			Class.forName(d.getDriverClass());
		}
		Connection c = DriverManager.getConnection(d.getJdbcUrl(),
			d.getUsername() == null ? "" : d.getUsername(),
			d.getPassword() == null ? "" : d.getPassword());
		if (Integer.valueOf(AiConstants.FLAG_YES).equals(d.getReadOnlyFlag())) {
			try {
				c.setReadOnly(true);
			} catch (Exception ignored) {
				// 部分驱动不支持，仍依赖 SqlSafetyGate + 表白名单
			}
		}
		return c;
	}

	private void fillDriver(AiDatasource body) {
		if (body.getDriverClass() != null && !body.getDriverClass().isBlank()) {
			return;
		}
		String t = body.getDbType() == null ? "" : body.getDbType().toLowerCase();
		switch (t) {
			case "mysql", "mariadb", "starrocks" -> body.setDriverClass("com.mysql.cj.jdbc.Driver");
			case "postgresql" -> body.setDriverClass("org.postgresql.Driver");
			case "oracle" -> body.setDriverClass("oracle.jdbc.OracleDriver");
			case "sqlserver" -> body.setDriverClass("com.microsoft.sqlserver.jdbc.SQLServerDriver");
			case "dm" -> body.setDriverClass("dm.jdbc.driver.DmDriver");
			case "kingbase" -> body.setDriverClass("com.kingbase8.Driver");
			default -> {
			}
		}
	}

	private AiDatasource require(Long id) {
		AiDatasource d = mapper.selectOneById(id);
		if (d == null) {
			throw new ServiceException(AiConstants.MSG_DS_MISSING);
		}
		return d;
	}

	private void mask(AiDatasource d) {
		if (d != null) {
			d.setPassword(null);
		}
	}
}
