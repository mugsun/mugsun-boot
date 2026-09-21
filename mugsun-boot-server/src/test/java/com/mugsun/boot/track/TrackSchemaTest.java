package com.mugsun.boot.track;

import cn.hutool.core.util.IdUtil;
import com.mugsun.boot.common.constant.TrackConstants;
import com.mybatisflex.core.datasource.DataSourceKey;
import com.mybatisflex.core.row.Db;
import com.mybatisflex.core.row.Row;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 埋点双库地基测试（G99 B1）：track 独立库 Flyway 迁移 / 月分区路由 / 幂等约束 / 业务库种子 / 物理隔离反证。
 * <p>track 数据源坐标与动态属性统一在 {@link AbstractTrackIntegrationTest}（多测试类共享同一 Spring 上下文，
 * 规避 warm-flow 静态 SpringUtil 的多上下文地雷）。
 * <p>分区表写入用 {@link Db} 原生 SQL + {@link DataSourceKey#use} 路由（实体批量插入在 B2）。
 */
class TrackSchemaTest extends AbstractTrackIntegrationTest {

	/** ① track 库 Flyway 迁移成功：11 张 track_* 基表齐备，0.1 基线 T1 历史 + 地理列 */
	@Test
	void trackFlywayMigrated() {
		List<Row> tables = DataSourceKey.use(TrackConstants.DS_KEY, () -> Db.selectListBySql(
			"SELECT tablename FROM pg_tables WHERE schemaname = 'public'"));
		Set<String> names = new java.util.HashSet<>();
		for (Row r : tables) {
			names.add(r.getString("tablename"));
		}
		assertThat(names).as("track 库应包含 11 张埋点基表").contains(
			"track_app", "track_event", "track_event_default", "track_session",
			"track_stats_5m", "track_stats_day", "track_stats_vitals", "track_rollup_cursor",
			"track_identity", "track_event_def", "track_event_data", "track_event_data_default", "track_replay");

		Row history = DataSourceKey.use(TrackConstants.DS_KEY, () -> Db.selectOneBySql(
			"SELECT count(*) AS c FROM flyway_schema_history WHERE script = ? AND success",
			"T1__baseline_0_1.sql"));
		assertThat(history.getLong("c")).as("track 库独立 Flyway 历史应记录 0.1 基线 T1").isEqualTo(1L);

		Row geoCols = DataSourceKey.use(TrackConstants.DS_KEY, () -> Db.selectOneBySql(
			"SELECT count(*) AS c FROM information_schema.columns WHERE table_schema = 'public'"
				+ " AND table_name IN ('track_event', 'track_app')"
				+ " AND column_name IN ('geo_lon', 'geo_lat', 'geo_enabled')"));
		assertThat(geoCols.getLong("c")).as("G106 三列：geo_lon/geo_lat/geo_enabled").isEqualTo(3L);
	}

	/** ② track_event 插入落到正确月分区（tableoid 反证实际落点） */
	@Test
	void eventInsertLandsInMonthPartition() {
		String eventId = UUID.randomUUID().toString();
		// 赋值语境规避 DataSourceKey.use 的 Runnable/Supplier 重载歧义（语句式 lambda 两者皆匹配）
		int inserted = DataSourceKey.use(TrackConstants.DS_KEY, () -> Db.updateBySql(
			"INSERT INTO track_event (id, event_id, app_key, event_name, client_ts, ts, received_at,"
				+ " distinct_id, session_id, tenant_id, url_path, props)"
				+ " VALUES (?, ?, 'demo-app', '$pageview', now(), now(), now(), ?, ?, '000000', '/home', '{\"k\":\"v\"}'::jsonb)",
			IdUtil.getSnowflakeNextId(), eventId, IdUtil.fastSimpleUUID(), IdUtil.fastSimpleUUID()));
		assertThat(inserted).as("事件插入应生效").isEqualTo(1);

		// 期望分区名取库内服务端时间（与 received_at=now() 同基准，规避 JVM 与库时区差）
		Row month = DataSourceKey.use(TrackConstants.DS_KEY, () ->
			Db.selectOneBySql("SELECT to_char(current_timestamp, 'YYYY_MM') AS ym"));
		String expectedPartition = "track_event_" + month.getString("ym");

		Row actual = DataSourceKey.use(TrackConstants.DS_KEY, () -> Db.selectOneBySql(
			"SELECT tableoid::regclass::text AS t FROM track_event WHERE event_id = ?", eventId));
		assertThat(actual.getString("t")).as("事件应落入当月分区").isEqualTo(expectedPartition);
	}

	/** ③ ON CONFLICT (event_id, received_at) DO NOTHING：同接收窗重复插不报错不重复 */
	@Test
	void onConflictDoNothingIdempotent() {
		String eventId = UUID.randomUUID().toString();
		// received_at 必须两次一致才能命中同接收窗唯一键（now() 各取各的则恒不冲突）
		LocalDateTime receivedAt = LocalDateTime.now();
		String sql = "INSERT INTO track_event (id, event_id, app_key, event_name, client_ts, ts, received_at,"
			+ " distinct_id, session_id, tenant_id) VALUES (?, ?, 'demo-app', '$click', ?, ?, ?, ?, ?, '000000')"
			+ " ON CONFLICT (event_id, received_at) DO NOTHING";
		int first = DataSourceKey.use(TrackConstants.DS_KEY, () -> Db.updateBySql(sql,
			IdUtil.getSnowflakeNextId(), eventId, receivedAt, receivedAt, receivedAt,
			IdUtil.fastSimpleUUID(), IdUtil.fastSimpleUUID()));
		int second = DataSourceKey.use(TrackConstants.DS_KEY, () -> Db.updateBySql(sql,
			IdUtil.getSnowflakeNextId(), eventId, receivedAt, receivedAt, receivedAt,
			IdUtil.fastSimpleUUID(), IdUtil.fastSimpleUUID()));
		assertThat(first).as("首次插入应生效").isEqualTo(1);
		assertThat(second).as("重复插入应被 DO NOTHING 吞掉").isEqualTo(0);

		Row count = DataSourceKey.use(TrackConstants.DS_KEY, () -> Db.selectOneBySql(
			"SELECT count(*) AS c FROM track_event WHERE event_id = ?", eventId));
		assertThat(count.getLong("c")).as("同 event_id 同接收窗应仅一行").isEqualTo(1L);
	}

	/** ④ 业务库 sys_menu 有 5 页 + 3 按钮共 8 条 track 权限锚点种子（V63） */
	@Test
	void bizDbTrackMenuSeeds() {
		Row pages = Db.selectOneBySql(
			"SELECT count(*) AS c FROM sys_menu WHERE is_deleted = 0 AND menu_type = 'C'"
				+ " AND permission IN (?, ?, ?, ?, ?)",
			TrackConstants.PERM_OVERVIEW_LIST, TrackConstants.PERM_EVENT_LIST, TrackConstants.PERM_PERF_LIST,
			TrackConstants.PERM_ERROR_LIST, TrackConstants.PERM_APP_LIST);
		assertThat(pages.getLong("c")).as("业务库应有 5 页 track 菜单种子").isEqualTo(5L);

		Row buttons = Db.selectOneBySql(
			"SELECT count(*) AS c FROM sys_menu WHERE is_deleted = 0 AND menu_type = 'F'"
				+ " AND permission IN (?, ?, ?)",
			TrackConstants.PERM_APP_ADD, TrackConstants.PERM_APP_EDIT, TrackConstants.PERM_REPLAY_VIEW);
		assertThat(buttons.getLong("c")).as("业务库应有 3 条 track 按钮权限种子").isEqualTo(3L);
	}

	/** ⑤ 主库无 track_* 表（物理隔离反证：埋点表只存在于 mugsun_track 库） */
	@Test
	void primaryDbHasNoTrackTables() {
		Row row = Db.selectOneBySql(
			"SELECT count(*) AS c FROM information_schema.tables WHERE table_schema = 'public'"
				+ " AND table_name LIKE 'track\\_%'");
		assertThat(row.getLong("c")).as("主库（业务库）不应存在任何 track_* 表").isEqualTo(0L);
	}
}
