package com.mugsun.boot.datascope;

import com.mugsun.boot.common.constant.DataScopeConstants;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 已启用的数据权限规则快照。查询期只读这份内存，不在拼 SQL 时查库。
 */
public final class DataScopeRuleIndex {

	public record Hit(String table, String deptColumn, String userColumn, int scopeType) {
	}

	private static volatile Map<String, Hit> hits = Map.of();
	private static final Set<String> managed = java.util.concurrent.ConcurrentHashMap.newKeySet();

	private DataScopeRuleIndex() {
	}

	public static Hit find(String tableName) {
		if (tableName == null) {
			return null;
		}
		return hits.get(tableName.toLowerCase());
	}

	public static List<Hit> snapshot() {
		return List.copyOf(hits.values());
	}

	/** 用当前启用规则重建受控表。同一张表保留更严的范围。 */
	public static synchronized void replace(List<Hit> enabled) {
		Map<String, Hit> next = new LinkedHashMap<>();
		if (enabled != null) {
			for (Hit hit : enabled) {
				if (hit == null || hit.table() == null) {
					continue;
				}
				String table = hit.table().toLowerCase();
				Hit prev = next.get(table);
				if (prev == null || rank(hit.scopeType()) > rank(prev.scopeType())) {
					next.put(table, new Hit(table, hit.deptColumn(), hit.userColumn(), hit.scopeType()));
				}
			}
		}
		for (String old : Set.copyOf(managed)) {
			if (!next.containsKey(old) && !"sys_user".equals(old)) {
				DataScopeTables.unregister(old);
			}
		}
		managed.clear();
		for (Hit hit : next.values()) {
			DataScopeTables.register(hit.table(), hit.deptColumn(), hit.userColumn());
			managed.add(hit.table());
		}
		if (!next.containsKey("sys_user")) {
			DataScopeTables.register("sys_user", "dept_id", "id");
		}
		hits = Map.copyOf(next);
	}

	public static int rank(int scope) {
		return switch (scope) {
			case DataScopeConstants.DEPT -> 2;
			case DataScopeConstants.DEPT_AND_CHILD, DataScopeConstants.CUSTOM_DEPT -> 3;
			case DataScopeConstants.SELF, DataScopeConstants.CUSTOM_SQL -> 4;
			default -> 0;
		};
	}

	/** 供测试观察当前快照是否为空。 */
	public static List<String> tables() {
		return new ArrayList<>(hits.keySet());
	}
}
