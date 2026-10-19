package com.mugsun.boot.datasource;

import com.mugsun.boot.datasource.mapper.SysTenantDatasourceMapper;
import com.mybatisflex.core.query.QueryWrapper;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * 租户数据源配置的域服务：给其他域（如删除租户）用，避免跨域直连 {@link SysTenantDatasourceMapper}。
 */
@Service
public class TenantDatasourceService {

	private final SysTenantDatasourceMapper datasourceMapper;
	private final ObjectProvider<TenantDataSourceRegistry> registry;

	public TenantDatasourceService(SysTenantDatasourceMapper datasourceMapper,
								   ObjectProvider<TenantDataSourceRegistry> registry) {
		this.datasourceMapper = datasourceMapper;
		this.registry = registry;
	}

	/**
	 * 逻辑删除某租户的全部数据源配置（实体继承 BaseEntity，delete 生成 UPDATE ... SET is_deleted = 1）。
	 * 加入调用方的事务；跨租户调用时由调用方忽略会话租户条件。
	 */
	public void removeByTenantCode(String tenantCode) {
		if (tenantCode == null || tenantCode.isBlank()) {
			return;
		}
		datasourceMapper.deleteByQuery(QueryWrapper.create().eq("tenant_code", tenantCode));
	}

	/** 已注册的租户独立数据源在事务提交后注销（回滚时保持原样）；没有事务同步时立即注销 */
	public void unregisterAfterCommit(Collection<String> tenantCodes) {
		TenantDataSourceRegistry reg = registry.getIfAvailable();
		if (reg == null || tenantCodes == null || tenantCodes.isEmpty()) {
			return;
		}
		List<String> codes = new ArrayList<>(tenantCodes);
		Runnable unregister = () -> codes.forEach(reg::unregister);
		if (TransactionSynchronizationManager.isSynchronizationActive()) {
			TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
				@Override
				public void afterCommit() {
					unregister.run();
				}
			});
		} else {
			unregister.run();
		}
	}
}
