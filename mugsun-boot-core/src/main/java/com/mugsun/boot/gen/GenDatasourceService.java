package com.mugsun.boot.gen;

import com.mugsun.boot.gen.entity.GenDatasource;
import com.mugsun.boot.gen.mapper.GenDatasourceMapper;
import com.mugsun.boot.tenant.TenantContext;
import com.mugsun.core.tool.exception.ServiceException;
import com.mybatisflex.core.paginate.Page;
import com.mybatisflex.core.query.QueryWrapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * 代码生成数据源。口令只落库，列表不回传。连不上也保存，并用说明告诉下一步。
 */
@Service
public class GenDatasourceService {

	private static final Logger log = LoggerFactory.getLogger(GenDatasourceService.class);
	private static final Pattern URL = Pattern.compile("^jdbc:(postgresql|kingbase8|opengauss|mysql|dm|oracle):\\S{1,280}$");
	private static final Pattern USER = Pattern.compile("^[A-Za-z0-9][A-Za-z0-9_@.-]{0,63}$");
	private static final String CONNECTED = "已连上，可以在代码生成里选择这套数据源";
	private static final String UNREACHABLE = "数据源暂时连不上。请确认地址、库名和网络可以访问，然后重新测试。";
	private static final String REJECTED = "数据源没有接受这个账号。请核对用户名和口令后再测试。";
	private static final String UNKNOWN_DB = "找不到这个库。请核对连接地址里的库名后再测试。";

	private final GenDatasourceMapper datasourceMapper;
	private final GenService genService;

	public GenDatasourceService(GenDatasourceMapper datasourceMapper, GenService genService) {
		this.datasourceMapper = datasourceMapper;
		this.genService = genService;
	}

	public Page<GenDatasource> page(long pageNum, long pageSize, String name) {
		QueryWrapper query = QueryWrapper.create().orderBy("id", false);
		if (name != null && !name.isBlank()) {
			query.like("ds_name", name.trim());
		}
		return datasourceMapper.paginate(pageNum, Math.min(pageSize, 100), query);
	}

	public List<GenDatasource> listConnected() {
		return datasourceMapper.selectListByQuery(QueryWrapper.create()
			.eq("connect_status", 1)
			.orderBy("id", true));
	}

	public GenDatasource require(Long id) {
		if (id == null) {
			throw new ServiceException("请从列表中选择一套数据源后再操作");
		}
		GenDatasource row = datasourceMapper.selectOneById(id);
		if (row == null) {
			throw new ServiceException("这套数据源不存在，或属于其他租户。请刷新列表后再试");
		}
		return row;
	}

	public List<Map<String, Object>> tables(Long id) {
		GenDatasource row = require(id);
		if (!Integer.valueOf(1).equals(row.getConnectStatus())) {
			throw new ServiceException("这套数据源上次没连上。请先在数据源页测试通过，再回来选择表。");
		}
		try {
			return genService.tablesOf(row.getJdbcUrl(), row.getUsername(), row.getPassword());
		} catch (ServiceException ex) {
			throw ex;
		}
	}

	public Map<String, Object> save(Long id, String name, String url, String username, String password, String remark) {
		String dsName = requireText(name, "请填写数据源名称", 64, "数据源名称请控制在 64 字以内");
		String jdbcUrl = normalizeUrl(url);
		String user = normalizeUser(username);
		String note = remark == null ? "" : remark.trim();
		if (note.length() > 200) {
			throw new ServiceException("备注请控制在 200 字以内");
		}
		QueryWrapper dup = QueryWrapper.create().eq("ds_name", dsName);
		if (id != null) {
			dup.ne("id", id);
		}
		if (datasourceMapper.selectCountByQuery(dup) > 0) {
			throw new ServiceException("数据源名称已存在，请换一个");
		}
		GenDatasource row;
		if (id == null) {
			String secret = requireText(password, "请填写数据库口令。保存后不会再显示，修改时留空则沿用原来的口令", 128,
				"数据库口令请控制在 128 字以内");
			row = new GenDatasource();
			row.setTenantId(TenantContext.current());
			row.setDsName(dsName);
			row.setJdbcUrl(jdbcUrl);
			row.setUsername(user);
			row.setPassword(secret);
			row.setDriverClass(driverOf(jdbcUrl));
			row.setRemark(note);
			row.sanitizeForInsert();
			applyProbe(row);
			datasourceMapper.insert(row);
		} else {
			row = require(id);
			if (password != null && !password.isBlank()) {
				if (password.trim().length() > 128) {
					throw new ServiceException("数据库口令请控制在 128 字以内");
				}
				row.setPassword(password.trim());
			}
			row.setDsName(dsName);
			row.setJdbcUrl(jdbcUrl);
			row.setUsername(user);
			row.setDriverClass(driverOf(jdbcUrl));
			row.setRemark(note);
			row.sanitizeForUpdate();
			applyProbe(row);
			datasourceMapper.update(row);
		}
		Map<String, Object> body = new LinkedHashMap<>();
		body.put("id", row.getId());
		body.put("connected", Integer.valueOf(1).equals(row.getConnectStatus()));
		body.put("message", row.getConnectMsg());
		return body;
	}

	@Transactional(rollbackFor = Exception.class)
	public List<GenDatasource> remove(List<Long> ids) {
		if (ids == null || ids.isEmpty()) {
			throw new ServiceException("请先勾选要删除的数据源");
		}
		List<GenDatasource> rows = new ArrayList<>();
		for (Long id : ids) {
			GenDatasource row = datasourceMapper.selectOneById(id);
			if (row == null) {
				throw new ServiceException("这套数据源不存在，或属于其他租户。请刷新列表后再试");
			}
			rows.add(row);
		}
		for (GenDatasource row : rows) {
			datasourceMapper.deleteById(row.getId());
		}
		return rows;
	}

	public Map<String, Object> test(Long id) {
		GenDatasource row = require(id);
		applyProbe(row);
		row.sanitizeForUpdate();
		datasourceMapper.update(row);
		Map<String, Object> body = new LinkedHashMap<>();
		body.put("connected", Integer.valueOf(1).equals(row.getConnectStatus()));
		body.put("message", row.getConnectMsg());
		return body;
	}

	public static Map<String, Object> view(GenDatasource row) {
		Map<String, Object> map = new LinkedHashMap<>();
		map.put("id", row.getId());
		map.put("dsName", row.getDsName());
		map.put("jdbcUrl", row.getJdbcUrl());
		map.put("username", row.getUsername());
		map.put("driverClass", row.getDriverClass());
		map.put("remark", row.getRemark());
		map.put("passwordSet", row.getPassword() != null && !row.getPassword().isBlank());
		map.put("connectStatus", row.getConnectStatus());
		map.put("connectMsg", row.getConnectMsg());
		map.put("connectTime", row.getConnectTime());
		map.put("createTime", row.getCreateTime());
		return map;
	}

	public static Map<String, Object> option(GenDatasource row) {
		Map<String, Object> map = new LinkedHashMap<>();
		map.put("id", row.getId());
		map.put("dsName", row.getDsName());
		return map;
	}

	private void applyProbe(GenDatasource row) {
		String message = probe(row.getJdbcUrl(), row.getUsername(), row.getPassword());
		row.setConnectTime(LocalDateTime.now());
		if (CONNECTED.equals(message)) {
			row.setConnectStatus(1);
			row.setConnectMsg(message);
		} else {
			row.setConnectStatus(0);
			row.setConnectMsg(message);
		}
	}

	private String probe(String url, String username, String password) {
		int previous = DriverManager.getLoginTimeout();
		DriverManager.setLoginTimeout(3);
		try (Connection connection = DriverManager.getConnection(url, username, password)) {
			if (connection.isValid(2)) {
				return CONNECTED;
			}
			return UNREACHABLE;
		} catch (SQLException ex) {
			log.warn("gen datasource probe failed state={}", ex.getSQLState());
			return hint(ex);
		} finally {
			DriverManager.setLoginTimeout(previous);
		}
	}

	static String hint(SQLException ex) {
		String state = ex.getSQLState() == null ? "" : ex.getSQLState();
		if ("28P01".equals(state) || "28000".equals(state)) {
			return REJECTED;
		}
		if ("3D000".equals(state)) {
			return UNKNOWN_DB;
		}
		return UNREACHABLE;
	}

	private static String normalizeUrl(String raw) {
		String url = requireText(raw, "请填写连接地址，例如 jdbc:postgresql://主机:端口/库名", 300,
			"连接地址请控制在 300 字以内");
		if (!URL.matcher(url).matches()) {
			throw new ServiceException("连接地址需要以 jdbc:postgresql、jdbc:kingbase8、jdbc:opengauss、jdbc:mysql、jdbc:dm 或 jdbc:oracle 开头，且不能包含空格");
		}
		String lower = url.toLowerCase();
		if (lower.contains("allowloadlocalinfile") || lower.contains("socketfactory") || lower.contains("init=")
			|| lower.contains("runscript")) {
			throw new ServiceException("连接地址包含不允许的参数。请只填写库地址、库名和必要的超时参数。");
		}
		return url;
	}

	private static String normalizeUser(String raw) {
		String user = requireText(raw, "请填写数据库用户名", 64, "数据库用户名请控制在 64 字以内");
		if (!USER.matcher(user).matches()) {
			throw new ServiceException("数据库用户名请使用字母或数字开头，只能包含字母、数字、下划线、点和连字符");
		}
		return user;
	}

	private static String driverOf(String url) {
		if (url.startsWith("jdbc:postgresql:")) {
			return "org.postgresql.Driver";
		}
		if (url.startsWith("jdbc:kingbase8:")) {
			return "com.kingbase8.Driver";
		}
		if (url.startsWith("jdbc:mysql:")) {
			return "com.mysql.cj.jdbc.Driver";
		}
		if (url.startsWith("jdbc:dm:")) {
			return "dm.jdbc.driver.DmDriver";
		}
		if (url.startsWith("jdbc:oracle:")) {
			return "oracle.jdbc.OracleDriver";
		}
		return "";
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
