package com.mugsun.boot.config;

import org.flywaydb.core.api.configuration.FluentConfiguration;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.flyway.FlywayConfigurationCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.util.StringUtils;

import javax.sql.DataSource;
import java.io.PrintWriter;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;

/**
 * 开发期把金仓等「PG 协议、却上报过低 major（如 8）」库接到与 PostgreSQL 同一套
 * {@code classpath:db/migration}：伪装 JDBC major/minor，避免 Flyway
 * {@code FlywayDbUpgradeRequiredException}；可选 default-schema 与业务表隔离。
 * <p>达梦仍走 {@code db/migration-dm}，不在此合并。
 */
@Configuration
public class FlywayPgCompatConfig {

	private static final org.slf4j.Logger log = LoggerFactory.getLogger(FlywayPgCompatConfig.class);

	@Value("${mugsun.flyway.spoof-pg-major-version:0}")
	private int spoofPgMajor;

	@Value("${mugsun.flyway.spoof-pg-minor-version:0}")
	private int spoofPgMinor;

	@Value("${mugsun.db.default-schema:}")
	private String defaultSchema;

	@Bean
	public FlywayConfigurationCustomizer flywayPgCompatCustomizer() {
		return (FluentConfiguration configuration) -> {
			if (spoofPgMajor > 0) {
				DataSource ds = configuration.getDataSource();
				if (ds != null) {
					configuration.dataSource(new VersionSpoofDataSource(ds, spoofPgMajor, spoofPgMinor));
					log.info("Flyway 启用 PG 版本伪装 major={}.{}（金仓等开发联调）", spoofPgMajor, spoofPgMinor);
				}
			}
			if (StringUtils.hasText(defaultSchema)) {
				configuration.schemas(defaultSchema);
				configuration.defaultSchema(defaultSchema);
				configuration.createSchemas(true);
				log.info("Flyway default-schema={}", defaultSchema);
			}
		};
	}

	/**
	 * 仅伪装 {@link DatabaseMetaData#getDatabaseMajorVersion()}/{@link DatabaseMetaData#getDatabaseMinorVersion()}，
	 * 其余委托真实连接——供 Flyway 版本门禁使用。
	 */
	static final class VersionSpoofDataSource implements DataSource {

		private final DataSource delegate;
		private final int major;
		private final int minor;

		VersionSpoofDataSource(DataSource delegate, int major, int minor) {
			this.delegate = delegate;
			this.major = major;
			this.minor = minor;
		}

		private Connection wrap(Connection c) {
			InvocationHandler h = (proxy, method, args) -> {
				if ("getMetaData".equals(method.getName()) && (args == null || args.length == 0)) {
					DatabaseMetaData real = c.getMetaData();
					return Proxy.newProxyInstance(
						DatabaseMetaData.class.getClassLoader(),
						new Class<?>[] { DatabaseMetaData.class },
						(p, m, a) -> {
							if ("getDatabaseMajorVersion".equals(m.getName())) {
								return major;
							}
							if ("getDatabaseMinorVersion".equals(m.getName())) {
								return minor;
							}
							return m.invoke(real, a);
						});
				}
				return method.invoke(c, args);
			};
			return (Connection) Proxy.newProxyInstance(
				Connection.class.getClassLoader(),
				new Class<?>[] { Connection.class },
				h);
		}

		@Override
		public Connection getConnection() throws SQLException {
			return wrap(delegate.getConnection());
		}

		@Override
		public Connection getConnection(String username, String password) throws SQLException {
			return wrap(delegate.getConnection(username, password));
		}

		@Override
		public PrintWriter getLogWriter() throws SQLException {
			return delegate.getLogWriter();
		}

		@Override
		public void setLogWriter(PrintWriter out) throws SQLException {
			delegate.setLogWriter(out);
		}

		@Override
		public void setLoginTimeout(int seconds) throws SQLException {
			delegate.setLoginTimeout(seconds);
		}

		@Override
		public int getLoginTimeout() throws SQLException {
			return delegate.getLoginTimeout();
		}

		@Override
		public java.util.logging.Logger getParentLogger() throws SQLFeatureNotSupportedException {
			return delegate.getParentLogger();
		}

		@Override
		public <T> T unwrap(Class<T> iface) throws SQLException {
			if (iface.isInstance(this)) {
				return iface.cast(this);
			}
			return delegate.unwrap(iface);
		}

		@Override
		public boolean isWrapperFor(Class<?> iface) throws SQLException {
			return iface.isInstance(this) || delegate.isWrapperFor(iface);
		}
	}
}
