package com.mugsun.boot.config;

import com.mugsun.boot.datascope.DataScopeRuleIndex;
import com.mugsun.boot.system.entity.SysDataScope;
import com.mugsun.boot.system.service.DataScopeService;
import com.mugsun.boot.tenant.TenantContext;
import org.springframework.boot.CommandLineRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 启动后把启用中的数据权限规则装进内存，查询时不再查库。
 */
@Component
@Order(20)
public class DataScopeRuleLoader implements CommandLineRunner {

	private final DataScopeService dataScopeService;

	public DataScopeRuleLoader(DataScopeService dataScopeService) {
		this.dataScopeService = dataScopeService;
	}

	@Override
	public void run(String... args) {
		reload();
	}

	public void reload() {
		List<SysDataScope> rows = TenantContext.ignore(dataScopeService::listEnabled);
		DataScopeRuleIndex.replace(rows.stream()
			.map(row -> new DataScopeRuleIndex.Hit(
				row.getTableName(), row.getDeptColumn(), row.getUserColumn(),
				row.getScopeType() == null ? 1 : row.getScopeType()))
			.toList());
	}
}
