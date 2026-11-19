package com.mugsun.boot.ai.support;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 问数和数据库节点的查询检查配置。
 * {@code mugsun.ai.sql.extra-functions}：在内置函数清单之外再放行的函数名。各家数据库都有自己的函数，
 * 内置清单列不全，用到时在这里追加；追加前要确认这个函数不读表、不执行 SQL，否则表白名单会被它绕过。
 */
@Component
@ConfigurationProperties(prefix = "mugsun.ai.sql")
public class AiSqlProperties {

	private static final Logger log = LoggerFactory.getLogger(AiSqlProperties.class);

	private List<String> extraFunctions = new ArrayList<>();

	public List<String> getExtraFunctions() {
		return extraFunctions;
	}

	public void setExtraFunctions(List<String> extraFunctions) {
		this.extraFunctions = extraFunctions == null ? new ArrayList<>() : extraFunctions;
		List<String> refused = SqlReadOnlyChecker.setExtraFunctions(this.extraFunctions);
		if (!refused.isEmpty()) {
			log.warn("mugsun.ai.sql.extra-functions 里这些名字没有生效（不是函数名，或属于不能放行的函数）: {}", refused);
		}
	}
}
