package com.mugsun.boot.config;

import com.mybatisflex.core.BaseMapper;
import com.mybatisflex.core.table.TableInfo;
import com.mybatisflex.core.table.TableInfoFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.context.EnvironmentAware;
import org.springframework.core.PriorityOrdered;
import org.springframework.core.env.Environment;
import org.springframework.lang.NonNull;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.lang.reflect.Field;
import java.lang.reflect.Proxy;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 金仓等环境：业务表在独立 schema 时，尽早为 Flex {@link TableInfo} 补 schema，
 * 避免未限定 {@code "sys_*"} 命中 SYS_CATALOG。
 */
@Component
public class DefaultDbSchemaApplier implements BeanPostProcessor, PriorityOrdered, EnvironmentAware {

	private static final Logger log = LoggerFactory.getLogger(DefaultDbSchemaApplier.class);

	private String schema;
	private final AtomicBoolean logged = new AtomicBoolean(false);

	@Override
	public void setEnvironment(@NonNull Environment environment) {
		this.schema = environment.getProperty("mugsun.db.default-schema");
	}

	@Override
	public int getOrder() {
		return HIGHEST_PRECEDENCE;
	}

	@Override
	public Object postProcessAfterInitialization(Object bean, String beanName) throws BeansException {
		if (!StringUtils.hasText(schema)) {
			return bean;
		}
		if (bean instanceof BaseMapper<?> mapper) {
			Class<?> mapperClass = resolveMapperClass(mapper);
			if (mapperClass != null) {
				apply(TableInfoFactory.ofMapperClass(mapperClass));
			}
			applyCachedMaps();
			if (logged.compareAndSet(false, true)) {
				log.info("Flex TableInfo 默认 schema={} 已启用（BeanPostProcessor）", schema);
			}
		}
		return bean;
	}

	private void apply(TableInfo info) {
		if (info == null || (info.getSchema() != null && !info.getSchema().isBlank())) {
			return;
		}
		String table = info.getTableName();
		// 埋点库（track_*）常与主库分实例/分 schema，勿强加主库 default-schema
		if (table != null && table.startsWith("track_")) {
			return;
		}
		info.setSchema(schema);
	}

	private static Class<?> resolveMapperClass(BaseMapper<?> mapper) {
		Class<?> clazz = mapper.getClass();
		if (Proxy.isProxyClass(clazz)) {
			for (Class<?> iface : clazz.getInterfaces()) {
				if (BaseMapper.class.isAssignableFrom(iface) && iface != BaseMapper.class) {
					return iface;
				}
			}
		}
		return clazz;
	}

	@SuppressWarnings("unchecked")
	private void applyCachedMaps() {
		for (String fieldName : new String[] { "entityTableMap", "mapperTableInfoMap", "tableInfoMap" }) {
			try {
				Field f = TableInfoFactory.class.getDeclaredField(fieldName);
				f.setAccessible(true);
				Map<?, TableInfo> map = (Map<?, TableInfo>) f.get(null);
				if (map == null) {
					continue;
				}
				for (TableInfo info : map.values()) {
					apply(info);
				}
			} catch (ReflectiveOperationException ignored) {
				// Flex 内部字段变更时忽略
			}
		}
	}
}
