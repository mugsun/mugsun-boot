# 埋点库 Flyway（独立数据源，前缀 T）

**0.1.0 基线**：仅 `T1__baseline_0_1.sql`（合并原 T1–T9）。

后续小版本追加 `T2__*.sql` 起递增（`T2` 归 UV 备份）。由 `TrackFlywayConfig` 指向本目录，与主库 `V*` 历史表隔离。

| 脚本 | 说明 |
|---|---|
| `T3__track_event_timeline_index.sql` | 用户细查时间线索引 `idx_event_user_ts_timeline` (app_key,user_id,ts,id) / `idx_event_distinct_ts_timeline` (app_key,distinct_id,ts,id)：仅 `ON ONLY` 建父表索引（不锁写）；T1 同名前缀的 received_at 旧索引保留 |
| `T4__track_event_timeline_index_partitions`（Java，`db.track.migration` 包） | 非事务迁移：逐分区 `CREATE INDEX CONCURRENTLY` → `ALTER INDEX … ATTACH PARTITION`，分区间短暂停顿；结束校验父/子索引 `indisvalid`，不全 valid 即迁移失败 |
