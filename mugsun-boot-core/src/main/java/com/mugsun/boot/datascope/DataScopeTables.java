package com.mugsun.boot.datascope;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 受控表注册表：声明哪些表参与数据权限过滤及其部门/本人归属列。
 * {@link DataScopeEngine} 仅对注册表内的表注入条件，字典/系统等表不受影响。
 * <p>内置系统表（sys_user）；业务模块如需行级数据权限，在启动期经 {@link #register} 登记
 * （表须带部门归属列与本人归属列，如 dept_id/create_by）——注册即被引擎接管，无需改引擎代码。
 */
public final class DataScopeTables {

	/** 受控表的部门列与本人列 */
	public record Columns(String deptColumn, String userColumn) {
	}

	/** 表名（小写）→ 归属列（启动期注册，运行期只读；ConcurrentHashMap 保注册/读取并发安全） */
	private static final Map<String, Columns> TABLES = new ConcurrentHashMap<>();

	static {
		TABLES.put("sys_user", new Columns("dept_id", "id"));
	}

	private DataScopeTables() {
	}

	/** 登记受控表（业务模块启动期调用；重复登记覆盖，幂等） */
	public static void register(String tableName, String deptColumn, String userColumn) {
		if (tableName == null || tableName.isBlank() || deptColumn == null || deptColumn.isBlank()
			|| userColumn == null || userColumn.isBlank()) {
			throw new IllegalArgumentException("受控表登记参数不完整：" + tableName);
		}
		TABLES.put(tableName.toLowerCase(), new Columns(deptColumn, userColumn));
	}

	/** 注销受控表（测试/热更场景） */
	public static void unregister(String tableName) {
		if (tableName != null) {
			TABLES.remove(tableName.toLowerCase());
		}
	}

	/** 已注册表名集（审计/排障用，快照拷贝） */
	public static java.util.Set<String> registered() {
		return java.util.Set.copyOf(TABLES.keySet());
	}

	/** 取受控表列；{@code null} 表示该表不受控（不注入） */
	public static Columns of(String tableName) {
		return tableName == null ? null : TABLES.get(tableName.toLowerCase());
	}
}
