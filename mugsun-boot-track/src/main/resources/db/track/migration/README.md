# 埋点库 Flyway（独立数据源，前缀 T）

**0.1.0 基线**：仅 `T1__baseline_0_1.sql`（合并原 T1–T9）。

后续小版本追加 `T2__*.sql`。由 `TrackFlywayConfig` 指向本目录，与主库 `V*` 历史表隔离。

| 脚本 | 说明 |
|---|---|
| `T2__track_event_timeline_index.sql` | 用户细查时间线索引 `idx_event_user_ts_timeline` (app_key,user_id,ts,id) / `idx_event_distinct_ts_timeline` (app_key,distinct_id,ts,id)；T1 同名前缀的 received_at 旧索引保留 |
