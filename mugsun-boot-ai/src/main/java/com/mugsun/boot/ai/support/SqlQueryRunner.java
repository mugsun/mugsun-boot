package com.mugsun.boot.ai.support;

import com.mugsun.boot.ai.AiConstants;
import com.mugsun.core.tool.exception.ServiceException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.SQLTimeoutException;
import java.sql.Statement;
import java.sql.Types;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * 执行已经过 {@link SqlReadOnlyChecker} 检查的查询：放进只读事务、限时、限行数；
 * 数据库报的错只写日志，交给用户的是固定的几句话。
 */
public final class SqlQueryRunner {

	public static final String MSG_CONNECT = "这次没有查出结果。请检查数据源能不能连上，再试一次";
	public static final String MSG_FAILED = "查询没有执行成功。请检查查询的写法（表名、列名、类型）后再试";
	public static final String MSG_TOO_BIG = "查询结果太大，已停止。请加上条件或减少返回的列后再试";

	public static final int DEFAULT_TIMEOUT_MS = 30_000;
	public static final int MAX_TIMEOUT_SECONDS = 120;

	private static final Logger log = LoggerFactory.getLogger(SqlQueryRunner.class);
	private static final Pattern PLAIN_NUMBER = Pattern.compile("-?\\d{1,18}(\\.\\d{1,18})?");

	private SqlQueryRunner() {
	}

	/** 毫秒换成秒：没填或填了 0 按 30 秒，最短 1 秒，最长 120 秒 */
	public static int timeoutSeconds(int timeoutMs) {
		long ms = timeoutMs <= 0 ? DEFAULT_TIMEOUT_MS : timeoutMs;
		return (int) Math.max(1, Math.min((ms + 999) / 1000, MAX_TIMEOUT_SECONDS));
	}

	public static String timeoutMessage(int seconds) {
		return "查询超过 " + seconds + " 秒，已停止。请缩小查询范围后再试";
	}

	/**
	 * @param params   要绑定的值；空表示 sql 里没有 ?
	 * @param pgFamily PostgreSQL 或金仓：值按「未指定类型」绑定，由数据库按上下文推断，效果和直接写字面量一样
	 */
	public static List<Map<String, Object>> run(Connection c, String sql, List<SqlReadOnlyChecker.Param> params,
												int maxRows, int timeoutMs, boolean pgFamily) {
		int seconds = timeoutSeconds(timeoutMs);
		int limit = Math.max(1, maxRows);
		boolean inTxn = false;
		try {
			// 不看数据源上的「只读」开关：这里只会执行查询，统一放进只读事务，写操作由数据库自己拒绝。
			// PostgreSQL 的驱动在自动提交下不会把只读发给服务端，所以要关掉自动提交
			quietly(() -> c.setReadOnly(true));
			try {
				c.setAutoCommit(false);
				inTxn = true;
			} catch (SQLException e) {
				log.warn("AI 查询没能开启只读事务 : {}", clip(e.getMessage()));
			}
			if (pgFamily && inTxn) {
				serverSideTimeout(c, seconds);
			}
			if (params == null || params.isEmpty()) {
				try (Statement st = c.createStatement()) {
					quietly(() -> st.setEscapeProcessing(false));
					applyLimits(st, limit, seconds);
					try (ResultSet rs = st.executeQuery(sql)) {
						return read(rs, limit);
					}
				}
			}
			try (PreparedStatement ps = c.prepareStatement(sql)) {
				applyLimits(ps, limit, seconds);
				bind(ps, params, pgFamily);
				try (ResultSet rs = ps.executeQuery()) {
					return read(rs, limit);
				}
			}
		} catch (SQLException e) {
			throw translate(e, seconds);
		} finally {
			if (inTxn) {
				quietly(c::rollback);
			}
		}
	}

	/** 数据库自己也掐一次表：应用重启、或者取消请求发不出去时，慢查询不会一直跑下去。只在本次事务内有效 */
	private static void serverSideTimeout(Connection c, int seconds) {
		try (Statement st = c.createStatement()) {
			st.execute("set local statement_timeout = " + (seconds * 1000L + 2000L));
		} catch (SQLException e) {
			quietly(c::rollback);
		}
	}

	private static void applyLimits(Statement st, int limit, int seconds) throws SQLException {
		try {
			st.setQueryTimeout(seconds);
		} catch (SQLException e) {
			log.warn("AI 查询的数据库驱动不支持设置超时 : {}", clip(e.getMessage()));
		}
		st.setMaxRows(limit);
	}

	private static List<Map<String, Object>> read(ResultSet rs, int limit) throws SQLException {
		ResultSetMetaData meta = rs.getMetaData();
		int cols = meta.getColumnCount();
		List<Map<String, Object>> rows = new ArrayList<>();
		while (rows.size() < limit && rs.next()) {
			Map<String, Object> row = new LinkedHashMap<>();
			for (int i = 1; i <= cols; i++) {
				row.put(meta.getColumnLabel(i), rs.getObject(i));
			}
			rows.add(row);
		}
		return rows;
	}

	private static void bind(PreparedStatement ps, List<SqlReadOnlyChecker.Param> params, boolean pgFamily)
		throws SQLException {
		for (int i = 0; i < params.size(); i++) {
			SqlReadOnlyChecker.Param param = params.get(i);
			Object value = param.value();
			int index = i + 1;
			if (value == null) {
				ps.setNull(index, pgFamily ? Types.NULL : Types.VARCHAR);
				continue;
			}
			if (pgFamily && bindUntyped(ps, index, value)) {
				continue;
			}
			if (value instanceof String text) {
				// 没写引号的变量如果是一串数字，按数字绑定：原来直接拼进 SQL 时它就是数字，limit ? 也只认数字
				if (!param.quoted() && PLAIN_NUMBER.matcher(text).matches()) {
					bindNumber(ps, index, new BigDecimal(text));
				} else {
					ps.setString(index, text);
				}
			} else if (value instanceof Long number) {
				ps.setLong(index, number);
			} else if (value instanceof BigDecimal number) {
				bindNumber(ps, index, number);
			} else if (value instanceof Boolean flag) {
				ps.setBoolean(index, flag);
			} else {
				ps.setString(index, String.valueOf(value));
			}
		}
	}

	private static boolean bindUntyped(PreparedStatement ps, int index, Object value) {
		String text = value instanceof BigDecimal number ? number.toPlainString() : String.valueOf(value);
		try {
			ps.setObject(index, text, Types.OTHER);
			return true;
		} catch (SQLException e) {
			return false;
		}
	}

	private static void bindNumber(PreparedStatement ps, int index, BigDecimal number) throws SQLException {
		if (number.scale() <= 0 && number.precision() - number.scale() <= 18) {
			ps.setLong(index, number.longValueExact());
		} else {
			ps.setBigDecimal(index, number);
		}
	}

	private static ServiceException translate(SQLException e, int seconds) {
		String state = e.getSQLState() == null ? "" : e.getSQLState();
		String message = e.getMessage() == null ? "" : e.getMessage();
		log.warn("AI 查询没有执行成功 sqlState={} : {}", state, clip(message));
		if (e instanceof SQLTimeoutException || state.equals("57014") || state.equals("HYT00")) {
			return new ServiceException(timeoutMessage(seconds));
		}
		if (state.equals("25006")) {
			return new ServiceException(AiConstants.MSG_SQL_UNSAFE);
		}
		if (message.contains("maxResultBuffer")) {
			return new ServiceException(MSG_TOO_BIG);
		}
		if (state.startsWith("08")) {
			return new ServiceException(MSG_CONNECT);
		}
		return new ServiceException(MSG_FAILED);
	}

	private static String clip(String message) {
		if (message == null) {
			return "";
		}
		String oneLine = message.replace('\r', ' ').replace('\n', ' ');
		return oneLine.length() > 300 ? oneLine.substring(0, 300) : oneLine;
	}

	private static void quietly(SqlAction action) {
		try {
			action.run();
		} catch (SQLException ignored) {
			// 尽力而为的设置：个别驱动不支持时不影响查询本身
		}
	}

	@FunctionalInterface
	private interface SqlAction {
		void run() throws SQLException;
	}
}
