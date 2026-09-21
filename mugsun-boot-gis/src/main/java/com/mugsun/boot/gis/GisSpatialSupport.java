package com.mugsun.boot.gis;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;

/**
 * 空间能力探测：当前库有没有 PostGIS 扩展与 {@code gis_feature} 要素表。
 *
 * <p>V79 把建表整段包在 DO 块里，没装扩展的库（含达梦，方言转换器会跳过 DO 块）不会有这张表。
 * 因此运行时必须先探测再决定走哪条路：有表就把空间查询下沉数据库，没有就回落到读
 * {@code gis_layer.data_json} 在 Java 侧算——两条路结果口径一致，只有性能差别。
 */
@Component
public class GisSpatialSupport {

	private static final Logger log = LoggerFactory.getLogger(GisSpatialSupport.class);

	private final JdbcTemplate jdbc;
	private volatile Boolean available;

	public GisSpatialSupport(DataSource dataSource) {
		this.jdbc = new JdbcTemplate(dataSource);
	}

	/** 探测结果缓存在内存：扩展与建表都是部署期动作，运行期不会来回变 */
	public boolean available() {
		Boolean cached = available;
		if (cached != null) {
			return cached;
		}
		boolean ok = probe();
		available = ok;
		if (ok) {
			log.info("GIS 空间查询下沉数据库：PostGIS + gis_feature 就绪");
		} else {
			// 降级是可预期路径（达梦 / 未装扩展的 PG），但必须让运维看见：
			// 否则容器重建后空间查询会悄悄慢数倍、矢量瓦片不可用，只留一行 INFO 极难发现。
			log.warn("GIS 空间查询回落 Java 侧：当前库无 PostGIS 或无 gis_feature 表（矢量瓦片不可用，空间查询走内存）");
		}
		return ok;
	}

	/** 仅供测试与运维接口用，强制下次重新探测 */
	public void reset() {
		this.available = null;
	}

	public JdbcTemplate jdbc() {
		return jdbc;
	}

	private boolean probe() {
		try {
			Integer hit = jdbc.queryForObject(
				"SELECT count(*) FROM pg_extension WHERE extname = 'postgis'", Integer.class);
			if (hit == null || hit == 0) {
				return false;
			}
			Integer table = jdbc.queryForObject(
				"SELECT count(*) FROM information_schema.tables WHERE table_name = 'gis_feature'", Integer.class);
			return table != null && table > 0;
		} catch (Exception e) {
			// 达梦 / 金仓等没有 pg_extension，抛异常即视为不支持，不打断启动
			return false;
		}
	}
}
