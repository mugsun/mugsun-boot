package com.mugsun.boot.config;

import com.mugsun.boot.common.perm.ApiScopeIndex;
import com.mugsun.boot.system.entity.SysApiScope;
import com.mugsun.boot.system.entity.SysRole;
import com.mugsun.boot.system.entity.SysRoleApiScope;
import com.mugsun.boot.system.service.ApiScopeService;
import com.mugsun.boot.tenant.TenantContext;
import org.springframework.boot.CommandLineRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 启动后把启用中的接口权限和角色授权装进内存。
 */
@Component
@Order(21)
public class ApiScopeRuleLoader implements CommandLineRunner {

	private final ApiScopeService apiScopeService;

	public ApiScopeRuleLoader(ApiScopeService apiScopeService) {
		this.apiScopeService = apiScopeService;
	}

	@Override
	public void run(String... args) {
		reload();
	}

	public void reload() {
		TenantContext.ignore(() -> {
			List<SysApiScope> rows = apiScopeService.listEnabled();
			List<SysRoleApiScope> links = apiScopeService.listAllRoleLinks();
			Map<Long, SysRole> roles = new HashMap<>();
			for (SysRole role : apiScopeService.listAllRoles()) {
				roles.put(role.getId(), role);
			}
			Map<Long, Set<String>> grants = new HashMap<>();
			for (SysRoleApiScope link : links) {
				SysRole role = roles.get(link.getRoleId());
				if (role == null || role.getRoleCode() == null) {
					continue;
				}
				grants.computeIfAbsent(link.getApiScopeId(), key -> new HashSet<>())
					.add(ApiScopeIndex.grantKey(role.getTenantId(), role.getRoleCode()));
			}
			ApiScopeIndex.replace(rows.stream()
				.filter(row -> row.getScopePath() != null)
				.map(row -> new ApiScopeIndex.Rule(row.getId(), row.getScopeName(), row.getScopePath()))
				.toList(), grants);
			return null;
		});
	}
}
