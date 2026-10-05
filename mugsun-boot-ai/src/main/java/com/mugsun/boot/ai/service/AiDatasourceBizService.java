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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Service
public class AiDatasourceBizService {

	private static final Set<String> TYPES = Set.of(
		"postgresql", "mysql", "kingbase", "dm", "mariadb", "starrocks");
	private static final String FAIL = "这次没有连上。请检查地址、用户名和密码后再试";

	private final AiModuleService moduleService;
	private final AiDatasourceMapper mapper;

	public AiDatasourceBizService(AiModuleService moduleService, AiDatasourceMapper mapper) {
		this.moduleService = moduleService;
		this.mapper = mapper;
	}

	public Page<AiDatasource> page(long pageNum, long pageSize, String name) {
		moduleService.requireEnabled();
		if (pageNum < 1) {
			throw new ServiceException("页码请从 1 开始");
		}
		if (pageSize < 1 || pageSize > 100) {
			throw new ServiceException("每页条数请在 1 到 100 之间");
		}
		QueryWrapper query = QueryWrapper.create().orderBy("id", false);
		if (name != null && !name.isBlank()) {
			String keyword = name.trim();
			if (keyword.length() > 64) {
				keyword = keyword.substring(0, 64);
			}
			query.like("name", keyword);
		}
		Page<AiDatasource> page = mapper.paginate(pageNum, pageSize, query);
		page.getRecords().forEach(this::mask);
		return page;
	}

	public AiDatasource detail(Long id) {
		moduleService.requireEnabled();
		AiDatasource row = require(id);
		mask(row);
		return row;
	}

	public AiDatasource submit(AiDatasource body) {
		moduleService.requireEnabled();
		if (body == null) {
			throw new ServiceException("请填写数据源名称。用来在列表里区分");
		}
		String name = body.getName() == null ? "" : body.getName().trim();
		if (name.isEmpty()) {
			throw new ServiceException("请填写数据源名称。用来在列表里区分");
		}
		if (name.length() > 64) {
			throw new ServiceException("数据源名称请控制在 64 个字以内");
		}
		String type = body.getDbType() == null ? "" : body.getDbType().trim().toLowerCase();
		if (!TYPES.contains(type)) {
			throw new ServiceException("类型请选择 PostgreSQL、MySQL、金仓、达梦、MariaDB 或 StarRocks");
		}
		String url = body.getJdbcUrl() == null ? "" : body.getJdbcUrl().trim();
		if (url.isEmpty()) {
			throw new ServiceException("请填写 JDBC 地址。需要以 jdbc: 开头，并和所选类型一致");
		}
		if (url.length() > 512 || url.contains(" ") || url.contains("<")) {
			throw new ServiceException("JDBC 地址请控制在 512 个字符以内，并且不要带空格");
		}
		if (!urlMatches(type, url)) {
			throw new ServiceException("JDBC 地址需要和所选类型一致。PostgreSQL 用 jdbc:postgresql://，MySQL 用 jdbc:mysql://");
		}
		String username = body.getUsername() == null ? "" : body.getUsername().trim();
		if (username.length() > 64) {
			throw new ServiceException("用户名请控制在 64 个字以内");
		}
		String password = body.getPassword() == null ? "" : body.getPassword();
		if (password.length() > 128) {
			throw new ServiceException("密码请控制在 128 个字以内");
		}
		String remark = body.getRemark() == null ? "" : body.getRemark().trim();
		if (remark.length() > 255) {
			throw new ServiceException("备注请控制在 255 个字以内");
		}
		if (body.getReadOnlyFlag() != null && body.getReadOnlyFlag() != 0 && body.getReadOnlyFlag() != 1) {
			throw new ServiceException("只读请选择开或关");
		}
		requireUnique(name, body.getId());
		body.setName(name);
		body.setDbType(type);
		body.setJdbcUrl(url);
		body.setUsername(username.isEmpty() ? null : username);
		body.setRemark(remark.isEmpty() ? null : remark);
		fillDriver(body);
		if (body.getId() == null) {
			body.sanitizeForInsert();
			body.setTenantId(TenantContext.current());
			if (body.getReadOnlyFlag() == null) {
				body.setReadOnlyFlag(AiConstants.FLAG_YES);
			}
			if (password.isEmpty()) {
				body.setPassword(null);
			}
			mapper.insert(body);
			mask(body);
			return body;
		}
		AiDatasource db = require(body.getId());
		db.setName(name);
		db.setDbType(type);
		db.setJdbcUrl(url);
		db.setUsername(username.isEmpty() ? null : username);
		db.setRemark(remark.isEmpty() ? null : remark);
		db.setDriverClass(body.getDriverClass());
		db.setReadOnlyFlag(body.getReadOnlyFlag() == null ? db.getReadOnlyFlag() : body.getReadOnlyFlag());
		if (!password.isEmpty()) {
			db.setPassword(password);
		}
		mapper.update(db);
		mask(db);
		return db;
	}

	public List<AiDatasource> remove(List<Long> ids) {
		moduleService.requireEnabled();
		if (ids == null || ids.isEmpty()) {
			throw new ServiceException("请先选择要删除的数据源");
		}
		List<AiDatasource> rows = new ArrayList<>();
		for (Long id : ids) {
			rows.add(require(id));
		}
		for (AiDatasource row : rows) {
			mapper.deleteById(row.getId());
		}
		return rows;
	}

	public Map<String, Object> test(Long id) {
		moduleService.requireEnabled();
		AiDatasource row = require(id);
		try (Connection connection = open(row)) {
			if (!connection.isValid(3)) {
				return result(false, FAIL);
			}
			return result(true, "已连上。可以在问数里选用这个数据源");
		} catch (Exception ignored) {
			return result(false, FAIL);
		}
	}

	public List<String> tables(Long id) {
		moduleService.requireEnabled();
		AiDatasource row = require(id);
		List<String> names = new ArrayList<>();
		try (Connection connection = open(row)) {
			DatabaseMetaData meta = connection.getMetaData();
			try (ResultSet rs = meta.getTables(connection.getCatalog(), null, "%", new String[]{"TABLE", "VIEW"})) {
				while (rs.next()) {
					names.add(rs.getString("TABLE_NAME"));
					if (names.size() >= 500) {
						break;
					}
				}
			}
		} catch (Exception ignored) {
			throw new ServiceException(FAIL);
		}
		return names;
	}

	public AiDatasource requireRaw(Long id) {
		return require(id);
	}

	public Connection open(AiDatasource row) throws Exception {
		if (row.getDriverClass() != null && !row.getDriverClass().isBlank()) {
			Class.forName(row.getDriverClass());
		}
		Connection connection = DriverManager.getConnection(row.getJdbcUrl(),
			row.getUsername() == null ? "" : row.getUsername(),
			row.getPassword() == null ? "" : row.getPassword());
		if (Integer.valueOf(AiConstants.FLAG_YES).equals(row.getReadOnlyFlag())) {
			try {
				connection.setReadOnly(true);
			} catch (Exception ignored) {
				// 部分驱动不支持只读，查询仍走表白名单
			}
		}
		return connection;
	}

	private void fillDriver(AiDatasource body) {
		body.setDriverClass(null);
		switch (body.getDbType()) {
			case "mysql", "mariadb", "starrocks" -> body.setDriverClass("com.mysql.cj.jdbc.Driver");
			case "postgresql" -> body.setDriverClass("org.postgresql.Driver");
			case "kingbase" -> body.setDriverClass(body.getJdbcUrl() != null && body.getJdbcUrl().startsWith("jdbc:kingbase8:")
				? "com.kingbase8.Driver" : "org.postgresql.Driver");
			case "dm" -> body.setDriverClass("dm.jdbc.driver.DmDriver");
			default -> {
			}
		}
	}

	private static boolean urlMatches(String type, String url) {
		return switch (type) {
			case "postgresql" -> url.startsWith("jdbc:postgresql:");
			case "kingbase" -> url.startsWith("jdbc:kingbase8:") || url.startsWith("jdbc:postgresql:");
			case "mysql", "mariadb", "starrocks" -> url.startsWith("jdbc:mysql:");
			case "dm" -> url.startsWith("jdbc:dm:");
			default -> false;
		};
	}

	private void requireUnique(String name, Long id) {
		QueryWrapper query = QueryWrapper.create().eq("name", name);
		if (id != null) {
			query.ne("id", id);
		}
		if (mapper.selectCountByQuery(query) > 0) {
			throw new ServiceException("已经有叫「" + name + "」的数据源。请换一个名称，或打开原来那个");
		}
	}

	private AiDatasource require(Long id) {
		AiDatasource row = mapper.selectOneById(id);
		if (row == null) {
			throw new ServiceException(AiConstants.MSG_DS_MISSING);
		}
		return row;
	}

	private void mask(AiDatasource row) {
		if (row != null) {
			row.setPassword(null);
		}
	}

	private static Map<String, Object> result(boolean ok, String message) {
		Map<String, Object> data = new LinkedHashMap<>();
		data.put("ok", ok);
		data.put("message", message);
		return data;
	}
}
