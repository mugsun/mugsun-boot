package com.mugsun.boot.datasource;

import com.mugsun.boot.common.crypto.Sm4Util;
import com.mugsun.boot.datasource.entity.SysTenantDatasource;
import com.mugsun.boot.datasource.mapper.SysTenantDatasourceMapper;
import com.mugsun.boot.gen.DbDialects;
import com.mugsun.boot.gen.SqlDialect;
import com.mugsun.core.tool.exception.ServiceException;
import com.mybatisflex.core.datasource.FlexDataSource;
import com.mybatisflex.core.query.QueryWrapper;
import com.mybatisflex.core.row.Db;
import com.mybatisflex.core.row.Row;
import com.mugsun.boot.tenant.TenantContext;
import com.zaxxer.hikari.HikariDataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.sql.Connection;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 租户数据源注册中心（隔离策略中央解析）：运行时把租户数据源加入 FlexDataSource，并按当前租户解析路由键。
 * <p>隔离策略：无配置=FIELD（主库 + tenant_id 字段隔离）；配置 isolation_type=1=DATASOURCE（独立库）；
 * =2=SCHEMA（同库独立 schema，专用连接池 connectionInitSql 设 search_path / ALTER SESSION CURRENT_SCHEMA，
 * 规避共享池 schema 切换泄漏）。
 * <p>注册前 testConnection 探活，坏配置不入池（不污染主库运行）。密码由 {@code Sm4TypeHandler} 透明解密。
 */
@Component
public class TenantDataSourceRegistry {

	private static final Logger log = LoggerFactory.getLogger(TenantDataSourceRegistry.class);
	public static final String DEFAULT_KEY = "primary";
	private static final String KEY_PREFIX = "tenant_";
	/** 隔离策略：独立库 */
	public static final int ISOLATION_DATABASE = 1;
	/** 隔离策略：独立 schema（同库） */
	public static final int ISOLATION_SCHEMA = 2;
	/** 探活连接超时（毫秒） */
	private static final long PROBE_TIMEOUT_MS = 3000L;

	private final DataSource dataSource;
	private final SysTenantDatasourceMapper datasourceMapper;
	/** 已注册且启用的租户编号 */
	private final Set<String> activeTenants = ConcurrentHashMap.newKeySet();

	public TenantDataSourceRegistry(DataSource dataSource, SysTenantDatasourceMapper datasourceMapper) {
		this.dataSource = dataSource;
		this.datasourceMapper = datasourceMapper;
	}

	/** 应用就绪后加载全部启用配置并注册（此时 Flyway 已建表）；单个坏配置只告警跳过、不阻断启动 */
	@EventListener(ApplicationReadyEvent.class)
	public void loadAll() {
		if (!(dataSource instanceof FlexDataSource)) {
			log.warn("当前 dataSource 非 FlexDataSource，租户独立数据源不可用");
			return;
		}
		encryptLegacyPasswords();
		Set<SysTenantDatasource> configs = TenantContext.ignore(() ->
			Set.copyOf(datasourceMapper.selectListByQuery(QueryWrapper.create().eq("status", 1))));
		for (SysTenantDatasource cfg : configs) {
			try {
				register(cfg);
			} catch (Exception e) {
				// 启用但未注册成功：该租户将静默回落主库（数据分裂面），error 级告警要求运维介入
				log.error("租户数据源 {} 注册失败（探活未通过），该租户请求将回落主库：{}", cfg.getTenantCode(), e.getMessage());
			}
		}
		log.info("租户独立数据源加载完成，共 {} 个", activeTenants.size());
	}

	/**
	 * 一次性回填：把存量明文密码重加密为 SM4 密文。
	 * 仅以「密文形态」判定（合法 Base64 且分组整数倍）：密文形态的行一律不动——
	 * 旧启发式 encrypt(decrypt(raw))!=raw 在密钥失配时会给密文逐层包裹（洋葱加密）永久毁数据，已废弃。
	 */
	private void encryptLegacyPasswords() {
		try {
			List<Row> rows = TenantContext.ignore(() ->
				Db.selectListBySql("SELECT id, ds_password FROM " + com.mugsun.boot.config.BizTables.of("sys_tenant_datasource")
					+ " WHERE is_deleted = 0"));
			for (Row r : rows) {
				String raw = r.getString("ds_password");
				if (raw == null || raw.isEmpty() || Sm4Util.looksLikeCipher(raw)) {
					continue;
				}
				String cipher = Sm4Util.encrypt(raw);
				if (cipher != null) {
					TenantContext.ignore(() -> Db.updateBySql(
						"UPDATE " + com.mugsun.boot.config.BizTables.of("sys_tenant_datasource")
							+ " SET ds_password = ? WHERE id = ?", cipher, r.get("id")));
					log.info("回填加密租户数据源密码：id={}", r.get("id"));
				}
			}
		} catch (Exception e) {
			log.warn("存量数据源密码加密回填失败：{}", e.getMessage());
		}
	}

	/** 注册（或重建）某租户数据源；探活未通过抛异常、不入池 */
	public void register(SysTenantDatasource cfg) {
		if (!(dataSource instanceof FlexDataSource flex)) {
			return;
		}
		String key = KEY_PREFIX + cfg.getTenantCode();
		HikariDataSource ds = new HikariDataSource();
		// JDBC-RCE 防护：注入池前校验连接串，拦多方言高危参数/内嵌库 scheme（保护独立数据源攻击面）
		ds.setJdbcUrl(com.mugsun.boot.security.JdbcUrlValidator.validate(cfg.getDsUrl()));
		ds.setUsername(cfg.getDsUsername());
		ds.setPassword(cfg.getDsPassword());
		ds.setMaximumPoolSize(5);
		ds.setPoolName(key);
		ds.setConnectionTimeout(PROBE_TIMEOUT_MS);
		// 懒初始化，探活时才真正建连，坏配置不在构造期阻塞
		ds.setInitializationFailTimeout(-1);
		// SCHEMA 隔离：专用池 connectionInitSql 按方言设 search_path / CURRENT_SCHEMA（随连接生命周期常驻，不泄漏）
		SqlDialect dialect = DbDialects.ofUrl(cfg.getDsUrl());
		if (isSchema(cfg)) {
			ds.setConnectionInitSql(dialect.schemaInitSql(cfg.getSchemaName()));
		}
		probe(ds, key, isSchema(cfg) ? cfg.getSchemaName() : null, dialect);
		// 探活通过后再替换旧的，避免探活失败却先毁了在用的旧源
		removeQuietly(flex, key);
		flex.addDataSource(key, ds);
		activeTenants.add(cfg.getTenantCode());
		log.info("注册租户数据源 {} -> {} ({})", key, cfg.getDsUrl(),
			isSchema(cfg) ? "schema:" + cfg.getSchemaName() : "database");
	}

	/** 探活：取一次连接并校验可用；SCHEMA 模式额外校验目标 schema 存在（PG SET search_path 对不存在 schema 不报错，需显式核验）；失败即关闭并抛异常，杜绝坏源污染运行池 */
	private void probe(HikariDataSource ds, String key, String schemaName, SqlDialect dialect) {
		try (Connection c = ds.getConnection()) {
			if (!c.isValid((int) (PROBE_TIMEOUT_MS / 1000))) {
				throw new ServiceException("数据源探活失败：连接不可用");
			}
			if (schemaName != null) {
				try (var ps = c.prepareStatement(dialect.schemaExistsSql())) {
					ps.setString(1, schemaName);
					try (var rs = ps.executeQuery()) {
						if (!rs.next()) {
							throw new ServiceException("目标 schema 不存在：" + schemaName);
						}
					}
				}
			}
		} catch (ServiceException e) {
			ds.close();
			throw e;
		} catch (Exception e) {
			ds.close();
			throw new ServiceException("数据源 " + key + " 探活失败：" + e.getMessage());
		}
	}

	private boolean isSchema(SysTenantDatasource cfg) {
		return Integer.valueOf(ISOLATION_SCHEMA).equals(cfg.getIsolationType())
			&& cfg.getSchemaName() != null && !cfg.getSchemaName().isBlank();
	}

	/** 注销某租户数据源，回落主库 */
	public void unregister(String tenantCode) {
		if (dataSource instanceof FlexDataSource flex) {
			removeQuietly(flex, KEY_PREFIX + tenantCode);
		}
		activeTenants.remove(tenantCode);
	}

	/** 解析当前租户的数据源路由键：启用独立源（库/schema）的租户返回其键，否则主库（FIELD 策略） */
	public String resolveDsKey(String tenantCode) {
		if (tenantCode != null && activeTenants.contains(tenantCode)) {
			return KEY_PREFIX + tenantCode;
		}
		return DEFAULT_KEY;
	}

	private void removeQuietly(FlexDataSource flex, String key) {
		try {
			if (flex.getDataSourceMap().containsKey(key)) {
				DataSource old = flex.getDataSourceMap().get(key);
				flex.removeDatasource(key);
				if (old instanceof HikariDataSource h) {
					h.close();
				}
			}
		} catch (Exception e) {
			log.warn("移除旧数据源 {} 失败：{}", key, e.getMessage());
		}
	}
}
