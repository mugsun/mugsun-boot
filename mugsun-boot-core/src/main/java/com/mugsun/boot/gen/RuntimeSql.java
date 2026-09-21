package com.mugsun.boot.gen;

/**
 * §5 运行时原生 SQL 模板：按 {@link SqlDialect} 出 PG {@code ON CONFLICT} 或 Oracle/达梦 {@code MERGE}。
 * 占位符顺序与调用方 {@code Db.updateBySql} 参数一致，切勿改序。
 */
public final class RuntimeSql {

	private RuntimeSql() {
	}

	/**
	 * 表格列配置 upsert：参数 (id, user_id, table_key, config_json)。
	 * 冲突键 (user_id, table_key) 且 is_deleted=0。
	 */
	public static String upsertTableColumn(SqlDialect d) {
		String ts = d.currentTimestamp();
		if (d.oracleFamily()) {
			return "MERGE INTO " + com.mugsun.boot.config.BizTables.of("sys_table_column") + " t "
				+ "USING (SELECT ? AS id, ? AS user_id, ? AS table_key, ? AS config_json FROM dual) s "
				+ "ON (t.user_id = s.user_id AND t.table_key = s.table_key AND t.is_deleted = 0) "
				+ "WHEN MATCHED THEN UPDATE SET t.config_json = s.config_json, t.update_time = " + ts + " "
				+ "WHEN NOT MATCHED THEN INSERT (id, user_id, table_key, config_json, create_time, update_time, is_deleted) "
				+ "VALUES (s.id, s.user_id, s.table_key, s.config_json, " + ts + ", " + ts + ", 0)";
		}
		return "insert into " + com.mugsun.boot.config.BizTables.of("sys_table_column")
			+ " (id, user_id, table_key, config_json, create_time, update_time, is_deleted) "
			+ "values (?, ?, ?, ?, " + ts + ", " + ts + ", 0) "
			+ "on conflict (user_id, table_key) where is_deleted = 0 "
			+ "do update set config_json = excluded.config_json, update_time = " + ts;
	}

	/**
	 * 工作台快捷入口 upsert：参数 (id, user_id, config_json)。
	 * 冲突键 (user_id) 且 is_deleted=0。
	 */
	public static String upsertWorkbenchShortcut(SqlDialect d) {
		String ts = d.currentTimestamp();
		if (d.oracleFamily()) {
			return "MERGE INTO " + com.mugsun.boot.config.BizTables.of("sys_workbench_shortcut") + " t "
				+ "USING (SELECT ? AS id, ? AS user_id, ? AS config_json FROM dual) s "
				+ "ON (t.user_id = s.user_id AND t.is_deleted = 0) "
				+ "WHEN MATCHED THEN UPDATE SET t.config_json = s.config_json, t.update_time = " + ts + " "
				+ "WHEN NOT MATCHED THEN INSERT (id, user_id, config_json, create_time, update_time, is_deleted) "
				+ "VALUES (s.id, s.user_id, s.config_json, " + ts + ", " + ts + ", 0)";
		}
		return "insert into " + com.mugsun.boot.config.BizTables.of("sys_workbench_shortcut")
			+ " (id, user_id, config_json, create_time, update_time, is_deleted) "
			+ "values (?, ?, ?, " + ts + ", " + ts + ", 0) "
			+ "on conflict (user_id) where is_deleted = 0 "
			+ "do update set config_json = excluded.config_json, update_time = " + ts;
	}

	/**
	 * 流水号当日记录 upsert：参数 (id, serial_code, record_date, last_number, last_time, gen_count)。
	 * 冲突键 (serial_code, record_date)；末值 GREATEST、计数累加。
	 */
	public static String upsertSerialRecord(SqlDialect d) {
		String ts = d.currentTimestamp();
		if (d.oracleFamily()) {
			return "MERGE INTO " + com.mugsun.boot.config.BizTables.of("sys_serial_number_record") + " t "
				+ "USING (SELECT ? AS id, ? AS serial_code, ? AS record_date, ? AS last_number, "
				+ "? AS last_time, ? AS gen_count FROM dual) s "
				+ "ON (t.serial_code = s.serial_code AND t.record_date = s.record_date) "
				+ "WHEN MATCHED THEN UPDATE SET "
				+ "t.last_number = GREATEST(t.last_number, s.last_number), "
				+ "t.last_time = s.last_time, "
				+ "t.gen_count = t.gen_count + s.gen_count "
				+ "WHEN NOT MATCHED THEN INSERT "
				+ "(id, serial_code, record_date, last_number, last_time, gen_count, create_time, is_deleted) "
				+ "VALUES (s.id, s.serial_code, s.record_date, s.last_number, s.last_time, s.gen_count, "
				+ ts + ", 0)";
		}
		return "insert into " + com.mugsun.boot.config.BizTables.of("sys_serial_number_record")
			+ " (id, serial_code, record_date, last_number, last_time, gen_count, create_time, is_deleted) "
			+ "values (?, ?, ?, ?, ?, ?, " + ts + ", 0) "
			+ "on conflict (serial_code, record_date) do update set "
			+ "last_number = greatest(sys_serial_number_record.last_number, excluded.last_number), "
			+ "last_time = excluded.last_time, "
			+ "gen_count = sys_serial_number_record.gen_count + excluded.gen_count";
	}

	/**
	 * 流水号末值单调回写：参数 (last_number, last_time, last_time, code)。
	 * GREATEST 防 Redis 并发回写倒退。
	 */
	public static String updateSerialLastMonotonic(SqlDialect d) {
		// GREATEST/COALESCE 在 PG 与 Oracle/达梦均可用；无 now() 差异
		return "update " + com.mugsun.boot.config.BizTables.of("sys_serial_number")
			+ " set last_number = greatest(coalesce(last_number, 0), ?), "
			+ "last_time = greatest(coalesce(last_time, ?), ?) where code = ?";
	}

	/** 流程抄送人插入：参数 (id, type, processed_by, associated) */
	public static String insertFlowUser(SqlDialect d) {
		return "insert into flow_user(id, type, processed_by, associated, create_time, del_flag) "
			+ "values (?, ?, ?, ?, " + d.currentTimestamp() + ", '0')";
	}

	/**
	 * 通知已读 PG 原子 upsert + xmax 判首读：参数 (id, notice_id, user_id)。
	 * Oracle/达梦无 xmax，勿调用本方法，改走应用层判定。
	 */
	public static String upsertNoticeReadPg() {
		String tbl = com.mugsun.boot.config.BizTables.of("sys_notice_read");
		String ts = SqlDialect.POSTGRES.currentTimestamp();
		return "insert into " + tbl
			+ " (id, notice_id, user_id, read_count, first_time, last_time, create_time, update_time, is_deleted) "
			+ "values (?, ?, ?, 1, " + ts + ", " + ts + ", " + ts + ", " + ts + ", 0) "
			+ "on conflict (notice_id, user_id) where is_deleted = 0 "
			+ "do update set read_count = " + tbl + ".read_count + 1, last_time = " + ts + ", update_time = " + ts + " "
			+ "returning (xmax = 0) as first_read";
	}

	/** 通知已读插入（Oracle/达梦首读）：参数 (id, notice_id, user_id) */
	public static String insertNoticeRead(SqlDialect d) {
		String ts = d.currentTimestamp();
		return "insert into " + com.mugsun.boot.config.BizTables.of("sys_notice_read")
			+ " (id, notice_id, user_id, read_count, first_time, last_time, create_time, update_time, is_deleted) "
			+ "values (?, ?, ?, 1, " + ts + ", " + ts + ", " + ts + ", " + ts + ", 0)";
	}

	/** 通知已读累加（Oracle/达梦再读）：参数 (notice_id, user_id) */
	public static String bumpNoticeRead(SqlDialect d) {
		String ts = d.currentTimestamp();
		return "update " + com.mugsun.boot.config.BizTables.of("sys_notice_read")
			+ " set read_count = read_count + 1, last_time = " + ts + ", update_time = " + ts
			+ " where notice_id = ? and user_id = ? and is_deleted = 0";
	}
}
