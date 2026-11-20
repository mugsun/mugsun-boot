# 埋点库的迁移脚本

这个目录是埋点库的 Flyway 迁移脚本。埋点库是独立的数据源，只支持 PostgreSQL。

它和主库的迁移分开管理：

| 项目 | 主库 | 埋点库 |
| --- | --- | --- |
| 脚本前缀 | `V` | `T` |
| 脚本目录 | `db/migration` | `db/track/migration` |
| 由谁执行 | `spring.flyway` | `TrackFlywayConfig` |
| 迁移历史 | 记在主库里 | 记在埋点库里 |

应用启动时，`TrackFlywayConfig` 先检查埋点库是否存在，不存在就创建，然后执行迁移。迁移失败时应用不会启动。

把 `mugsun.track.flyway-enabled` 设为 `false`，可以跳过建库和迁移。这时埋点库要事先手工建好。

## 规则

- `T1__baseline_0_1.sql` 是 0.1.0 的发布基线，合并了更早的 T1 到 T9。它已经发布，不能修改。
- 之后每次改库都新增一个 `Tn__说明.sql`，版本号从 T2 起递增。

已发布的清单和主库共用，是仓库根目录的 `db/RELEASED.sha256`。

## 脚本清单

| 脚本 | 内容 |
| --- | --- |
| `T1__baseline_0_1.sql` | 0.1.0 的发布基线 |
| `T2__track_stats_day_uv_backup.sql` | 备份表 `track_stats_day_uv_backup`。回填历史 UV 之前，把要改的行的旧值存在这里 |
| `T3__track_event_timeline_index.sql` | 用户时间线用的两个索引，只建在父表上 |
| `T4__track_event_timeline_index_partitions` | Java 迁移，在 `db.track.migration` 包里。给各个分区建时间线索引 |
| `T5__track_error_issue.sql` | 错误问题单表 `track_error_issue`，支持指派、解决和复发 |

新增迁移时，请在这张表里加一行。

### T3 和 T4：时间线索引

T3 建两个索引：

| 索引 | 列 |
| --- | --- |
| `idx_event_user_ts_timeline` | `(app_key, user_id, ts, id)` |
| `idx_event_distinct_ts_timeline` | `(app_key, distinct_id, ts, id)` |

`track_event` 是按月分区的表。T3 用 `ON ONLY` 只在父表上建索引，这样不会锁住写入。

T1 里的两个旧索引保留不动，它们的最后一列是 `received_at`：

- `idx_event_user_timeline`
- `idx_event_distinct_timeline`

T4 给各个分区补上索引。它不在事务里执行，启动时也不会同步给大分区建索引：

- 空的分区：先 `CREATE INDEX CONCURRENTLY`，再校验 `indisvalid`，最后 `ATTACH PARTITION`。
- 已有数据的分区：启动时跳过，由 `TrackTimelineIndexBackfillJob` 在低峰期后台补建。

T4 不会抛出异常，出问题时只记一条 WARN 日志。

后台补建由 `mugsun.track.timeline-index.backfill-enabled` 控制，默认打开。

## 对数据库连接的要求

埋点库的连接必须直连 PostgreSQL，或者走 PgBouncer 的 session 模式。

PgBouncer 的 transaction 模式不支持。

原因是这里用的都是会话级的锁：

- T4、后台补建和 `TrackPartitionJob` 共用下面这把锁。
- 埋点库的 Flyway 也用会话级的锁，对应的设置是 `postgresql.transactional.lock=false`。

```sql
SELECT pg_advisory_lock(hashtext('track_timeline_index'));
```

多个节点同时启动时，后启动的节点会等前一个节点迁移完。`lockRetryCount` 设成了 600，最多等 10 分钟左右。
