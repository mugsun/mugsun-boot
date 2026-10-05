package com.mugsun.boot.config;

import com.mugsun.boot.common.perm.ApiScopeIndex;
import com.mugsun.boot.system.entity.SysApiScope;
import com.mugsun.boot.system.entity.SysRole;
import com.mugsun.boot.system.entity.SysRoleApiScope;
import com.mugsun.boot.system.mapper.SysApiScopeMapper;
import com.mugsun.boot.system.mapper.SysRoleApiScopeMapper;
import com.mugsun.boot.system.mapper.SysRoleMapper;
import com.mugsun.boot.tenant.TenantContext;
import com.mybatisflex.core.query.QueryWrapper;
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

	private final SysApiScopeMapper apiScopeMapper;
	private final SysRoleApiScopeMapper linkMapper;
	private final SysRoleMapper roleMapper;

	public ApiScopeRuleLoader(SysApiScopeMapper apiScopeMapper, SysRoleApiScopeMapper linkMapper,
							  SysRoleMapper roleMapper) {
		this.apiScopeMapper = apiScopeMapper;
		this.linkMapper = linkMapper;
		this.roleMapper = roleMapper;
	}

	@Override
	public void run(String... args) {
		reload();
	}

	public void reload() {
		TenantContext.ignore(() -> {
			List<SysApiScope> rows = apiScopeMapper.selectListByQuery(QueryWrapper.create().eq("status", 1));
			List<SysRoleApiScope> links = linkMapper.selectListByQuery(QueryWrapper.create());
			Map<Long, SysRole> roles = new HashMap<>();
			for (SysRole role : roleMapper.selectAll()) {
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
