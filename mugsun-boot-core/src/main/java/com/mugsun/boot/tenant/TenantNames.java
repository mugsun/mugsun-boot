package com.mugsun.boot.tenant;

import com.mugsun.boot.config.BizTables;
import com.mybatisflex.core.row.Db;
import com.mybatisflex.core.row.Row;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 按租户编号聚合的图表数据（[{name,value}]，name 为 tenant_id）：把 name 换成租户名称，没有名称时保留编号。
 * 名称只查平台表 sys_tenant（经 BizTables 加 schema），不改聚合 SQL，三种方言通用。
 */
public final class TenantNames {

	private TenantNames() {
	}

	public static List<Row> label(List<Row> rows) {
		if (rows == null || rows.isEmpty()) {
			return rows;
		}
		Map<String, String> names = toNameMap(Db.selectListBySql(nameSql(BizTables.of("sys_tenant"))));
		for (Row row : rows) {
			Object code = row.get("name");
			String name = code == null ? null : names.get(code.toString());
			if (name != null) {
				row.set("name", name);
			}
		}
		return rows;
	}

	/** 列名用带引号的小写别名：达梦/金仓等未加引号的列名会返回大写，Row.get 区分大小写会取到 null。 */
	static String nameSql(String table) {
		return "SELECT tenant_code AS \"tenant_code\", tenant_name AS \"tenant_name\" FROM " + table + " WHERE is_deleted = 0";
	}

	static Map<String, String> toNameMap(List<Row> tenants) {
		Map<String, String> names = new HashMap<>();
		for (Row t : tenants) {
			Object code = t.get("tenant_code");
			Object name = t.get("tenant_name");
			if (code != null && name != null && !name.toString().isBlank()) {
				names.put(code.toString(), name.toString().trim());
			}
		}
		return names;
	}
}
