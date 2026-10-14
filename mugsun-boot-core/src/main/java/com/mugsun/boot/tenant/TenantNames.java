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
		Map<String, String> names = new HashMap<>();
		for (Row t : Db.selectListBySql("SELECT tenant_code, tenant_name FROM " + BizTables.of("sys_tenant") + " WHERE is_deleted = 0")) {
			Object code = t.get("tenant_code");
			Object name = t.get("tenant_name");
			if (code != null && name != null && !name.toString().isBlank()) {
				names.put(code.toString(), name.toString().trim());
			}
		}
		for (Row row : rows) {
			Object code = row.get("name");
			String name = code == null ? null : names.get(code.toString());
			if (name != null) {
				row.set("name", name);
			}
		}
		return rows;
	}
}
