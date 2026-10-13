# 埋点库 Flyway（独立数据源，前缀 T）

**0.1.0 基线**：仅 `T1__baseline_0_1.sql`（合并原 T1–T9）。

后续小版本追加 `T2__*.sql` 起递增（`T2` 归 UV 备份）。由 `TrackFlywayConfig` 指向本目录，与主库 `V*` 历史表隔离。

| 脚本 | 说明 |
|---|---|
| `T3__track_event_timeline_index.sql` | 用户细查时间线索引 `idx_event_user_ts_timeline` (app_key,user_id,ts,id) / `idx_event_distinct_ts_timeline` (app_key,distinct_id,ts,id)：仅 `ON ONLY` 建父表索引（不锁写）；T1 同名前缀的 received_at 旧索引保留 |
| `T4__track_event_timeline_index_partitions`（Java，`db.track.migration` 包） | 非事务迁移，**启动期不同步建大分区索引**：只给空分区 `CREATE INDEX CONCURRENTLY` → 校验 `indisvalid` → `ATTACH PARTITION`；非空存量分区跳过，交给 `TrackTimelineIndexBackfillJob` 低峰期后台补（`mugsun.track.timeline-index.backfill-enabled`）。永不抛异常，只记 WARN |

**连接要求**：T4、后台补建、`TrackPartitionJob` 共用会话级 `pg_advisory_lock(hashtext('track_timeline_index'))`，埋点 Flyway 也用会话级锁（`postgresql.transactional.lock=false`，`lockRetryCount=600`）。埋点库连接须直连 PG 或走 PgBouncer **session** 模式，**不支持 PgBouncer transaction pooling**。
