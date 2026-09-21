package com.mugsun.boot.track;

import com.mugsun.boot.common.constant.TrackConstants;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.configuration.FluentConfiguration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.regex.Pattern;

/**
 * 埋点库（track 独立数据源）初始化与独立 Flyway 迁移（G99 地基）。
 * <p>背景：{@code spring.flyway} 只绑主数据源（primary），不会管埋点库；埋点库使用独立迁移目录
 * {@value TrackConstants#FLYWAY_LOCATIONS}、独立版本序列（{@value TrackConstants#FLYWAY_SQL_PREFIX}1 起，
 * 与主库 V 序列互不干扰），启动时自动 migrate。
 * <p>执行顺序（同一 bean 方法内串行保证，先建库后迁移）：
 * ① 用 primary 连接检查并创建埋点 database（PG 无 CREATE DATABASE IF NOT EXISTS，先查 pg_database；
 * CREATE DATABASE 不能在事务内，须用自动提交的裸连接）；
 * ② 以埋点库连接构建独立 Flyway 并立即 migrate。
 * <p>L1 约定：埋点库与主库同 PG 实例、同账号——建库坐标取 primary，迁移 URL 仅替换库名。
 * L2 / 信创混合：主库金仓、埋点独立本机 PG——{@link #samePgInstance} 为 false 时，
 * 建库与迁移一律走 track URL（禁止拿金仓 primary 查 {@code pg_database}）。
 * <p>返回值刻意不声明为 Flyway 类型：spring.flyway 自动配置的 flywayInitializer 按单实例注入 Flyway，
 * 容器中一旦出现第二个 Flyway 类型 bean，主库迁移会因注入不唯一而启动失败；Flyway 实例仅本类内部持有。
 */
@Configuration(proxyBeanMethods = false)
public class TrackFlywayConfig {

	private static final Logger log = LoggerFactory.getLogger(TrackFlywayConfig.class);

	/** JDBC URL  scheme 前缀（埋点库锁定 PostgreSQL，见多方言降级声明） */
	private static final String PG_URL_PREFIX = "jdbc:postgresql:";
	/** 库名合法字符（CREATE DATABASE 不支持参数绑定，标识符校验后双引号包裹，防注入） */
	private static final Pattern DB_NAME_PATTERN = Pattern.compile("[A-Za-z0-9_]+");
	/** PG 错误码：duplicate_database（多节点并发启动同时建库时后建者命中，视为已建） */
	private static final String DUPLICATE_DATABASE_SQL_STATE = "42P04";
	/** PG 错误码：insufficient_privilege（应用账号无 CREATEDB 权限） */
	private static final String INSUFFICIENT_PRIVILEGE_SQL_STATE = "42501";

	/**
	 * 建库 + 迁移埋点库，返回初始化标记 bean（承载库名，供健康检查/日志辨识）。
	 * 迁移失败即启动失败（fail-fast）：埋点库不可用时不应带病运行。
	 */
	@Bean
	public TrackFlywayInitializer trackFlywayInitializer(Environment env) throws SQLException {
		String primaryUrl = env.getRequiredProperty("mybatis-flex.datasource.primary.url");
		String primaryUser = env.getRequiredProperty("mybatis-flex.datasource.primary.username");
		String primaryPassword = env.getRequiredProperty("mybatis-flex.datasource.primary.password");
		String trackUrl = env.getRequiredProperty("mybatis-flex.datasource.track.url");
		// 账号缺省与 primary 一致（L1 同实例同账号；L2 可在 track 数据源上单独配置）
		String trackUser = env.getProperty("mybatis-flex.datasource.track.username", primaryUser);
		String trackPassword = env.getProperty("mybatis-flex.datasource.track.password", primaryPassword);
		String trackDb = parseDbName(trackUrl);

		// 信创联调：主库金仓手工灌库后可关埋点 Flyway（分区等 PG 专属语法）；或 track 仍指本地 PG
		if (!env.getProperty("mugsun.track.flyway-enabled", Boolean.class, true)) {
			log.warn("mugsun.track.flyway-enabled=false，跳过埋点库建库与 Flyway（假定 {} 已就绪）", trackDb);
			return new TrackFlywayInitializer(trackDb);
		}

		// L1：主/埋点同 PG 实例 → 用 primary 建库，再替换库名迁移
		// L2 / 信创混合：主库金仓、埋点本机 PG → 禁止用 primary（无 pg_database），直接对 track URL 迁
		final String migrateUrl;
		if (samePgInstance(primaryUrl, trackUrl)) {
			ensureDatabase(primaryUrl, primaryUser, primaryPassword, trackDb);
			migrateUrl = replaceDbName(primaryUrl, trackDb);
		} else {
			log.info("埋点库与主库不同实例，按 track URL 独立 Flyway：{}", maskJdbcUrl(trackUrl));
			// 已存在则直连；不存在再连维护库 postgres 建库（勿用金仓 primary）
			try (Connection ignored = DriverManager.getConnection(trackUrl, trackUser, trackPassword)) {
				log.info("埋点库 {} 已可连", trackDb);
			} catch (SQLException connectFail) {
				ensureDatabase(replaceDbName(trackUrl, "postgres"), trackUser, trackPassword, trackDb);
			}
			migrateUrl = trackUrl;
		}

		Flyway flyway = new FluentConfiguration()
			.dataSource(migrateUrl, trackUser, trackPassword)
			.locations(TrackConstants.FLYWAY_LOCATIONS)
			.sqlMigrationPrefix(TrackConstants.FLYWAY_SQL_PREFIX)
			.baselineOnMigrate(true)
			.baselineVersion("0")
			// 与主库 spring.flyway 约定一致：SQL 内 ${} 占位符不替换（DO $$ 块/函数字面量防误吞）
			.placeholderReplacement(false)
			.load();
		int applied = flyway.migrate().migrationsExecuted;
		log.info("埋点库 Flyway 迁移完成：{}（本次执行 {} 个迁移脚本）", trackDb, applied);
		return new TrackFlywayInitializer(trackDb);
	}

	/** 检查并创建埋点 database：已存在直接返回；多节点并发建库时后建者吞 duplicate_database */
	private void ensureDatabase(String primaryUrl, String user, String password, String trackDb) throws SQLException {
		try (Connection conn = DriverManager.getConnection(primaryUrl, user, password)) {
			// 裸连接默认自动提交，CREATE DATABASE 不在事务块内（PG 硬性要求）
			try (PreparedStatement ps = conn.prepareStatement("SELECT 1 FROM pg_database WHERE datname = ?")) {
				ps.setString(1, trackDb);
				try (ResultSet rs = ps.executeQuery()) {
					if (rs.next()) {
						return;
					}
				}
			}
			try (Statement st = conn.createStatement()) {
				st.execute("CREATE DATABASE \"" + trackDb + "\"");
				log.info("埋点库不存在，已创建：{}", trackDb);
			} catch (SQLException e) {
				if (DUPLICATE_DATABASE_SQL_STATE.equals(e.getSQLState())) {
					log.info("埋点库 {} 已被并发节点创建，继续迁移", trackDb);
					return;
				}
				if (INSUFFICIENT_PRIVILEGE_SQL_STATE.equals(e.getSQLState())) {
					// 账号缺 CREATEDB：给出可操作的修复路径（授权 / 预先手工建库），替代裸栈启动崩溃
					throw new SQLException("创建埋点库 " + trackDb + " 失败：账号 " + user + " 无 CREATEDB 权限——"
						+ "执行 ALTER USER " + user + " CREATEDB，或预先手工建库（见 scripts/init-db.sql）", e);
				}
				throw e;
			}
		}
	}

	/** 从 JDBC URL 解析库名段（{@code jdbc:postgresql://host:port/dbname?params} 的 dbname） */
	static String parseDbName(String jdbcUrl) {
		int pathStart = pathStart(jdbcUrl);
		int queryStart = jdbcUrl.indexOf('?', pathStart);
		String db = queryStart < 0 ? jdbcUrl.substring(pathStart + 1) : jdbcUrl.substring(pathStart + 1, queryStart);
		if (!DB_NAME_PATTERN.matcher(db).matches()) {
			throw new IllegalStateException("埋点数据源 URL 库名非法（仅允许字母/数字/下划线）：" + jdbcUrl);
		}
		return db;
	}

	/** 替换 JDBC URL 的库名段，保留 host/port 与查询参数 */
	static String replaceDbName(String jdbcUrl, String dbName) {
		int pathStart = pathStart(jdbcUrl);
		int queryStart = jdbcUrl.indexOf('?', pathStart);
		String base = jdbcUrl.substring(0, pathStart + 1);
		String query = queryStart < 0 ? "" : jdbcUrl.substring(queryStart);
		return base + dbName + query;
	}

	/** 同 PG 实例判定：scheme+host+port 一致（忽略库名与 query）。非 PG 主库一律视为异实例（信创混合） */
	static boolean samePgInstance(String urlA, String urlB) {
		if (!urlA.startsWith(PG_URL_PREFIX) || !urlB.startsWith(PG_URL_PREFIX)) {
			return false;
		}
		return instanceKey(urlA).equals(instanceKey(urlB));
	}

	private static String instanceKey(String jdbcUrl) {
		int pathStart = pathStart(jdbcUrl);
		return jdbcUrl.substring(0, pathStart).toLowerCase();
	}

	private static String maskJdbcUrl(String jdbcUrl) {
		int q = jdbcUrl.indexOf('?');
		return q < 0 ? jdbcUrl : jdbcUrl.substring(0, q);
	}

	private static int pathStart(String jdbcUrl) {
		if (!jdbcUrl.startsWith(PG_URL_PREFIX)) {
			throw new IllegalStateException("埋点库锁定 PostgreSQL，数据源 URL 须以 " + PG_URL_PREFIX + " 开头：" + jdbcUrl);
		}
		// scheme 后紧跟 "//"（host 起始），库名段的 '/' 须从 "//" 之后开始找
		int pathStart = jdbcUrl.indexOf('/', PG_URL_PREFIX.length() + 2);
		if (pathStart < 0 || pathStart == jdbcUrl.length() - 1) {
			throw new IllegalStateException("数据源 URL 缺少库名段：" + jdbcUrl);
		}
		return pathStart;
	}

	/** 初始化完成标记（库名载体）；非 Flyway 类型，避免干扰 spring.flyway 主库自动装配 */
	public record TrackFlywayInitializer(String database) {
	}
}
