package com.mugsun.boot.datascope;

import com.mybatisflex.core.FlexGlobalConfig;
import com.mybatisflex.core.dialect.DbType;
import com.mybatisflex.core.dialect.DialectFactory;
import com.mybatisflex.spring.boot.MyBatisFlexCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 数据权限方言装配：Flex 初始化期（首次 getDialect 之前）为各 {@link DbType} 注册
 * {@link MugsunDataScopeDialect}，覆盖内置方言以启用 prepareAuth 行级授权钩子。
 * {@code DialectFactory.registerDialect} 用 computeIfAbsent 语义，故必须在
 * {@code MyBatisFlexCustomizer}（Flex 装配早期）注册方能生效。
 */
@Configuration
public class DataScopeDialectConfig {

	@Bean
	public MyBatisFlexCustomizer dataScopeDialectCustomizer(DataScopeEngine engine) {
		return (FlexGlobalConfig config) -> {
			for (DbType dbType : DbType.values()) {
				DialectFactory.registerDialect(dbType, MugsunDataScopeDialect.create(dbType, engine));
			}
		};
	}
}
