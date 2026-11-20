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

## 目录里有什么

| 迁移 | 内容 |
| --- | --- |
| `T1__baseline_0_2.sql` | 0.2 的基线。全新安装只执行这一个 SQL 文件 |
| `T4__track_event_timeline_index_partitions` | Java 迁移，在 `db.track.migration` 包里。给各个分区建时间线索引 |

全新的埋点库迁移完，`flyway_schema_history` 里只有这两条记录。

基线由 0.1.0 的基线和它之后的 T2、T3、T5 按版本号顺序拼成，语句没有改动。每一段前面有一行 `-- 原 <文件名>`。

| 原脚本 | 内容 |
| --- | --- |
| `T1__baseline_0_1.sql` | 0.1.0 的发布基线，合并了更早的 T1 到 T9 |
| `T2__track_stats_day_uv_backup.sql` | 备份表 `track_stats_day_uv_backup`。回填历史 UV 之前，把要改的行的旧值存在这里 |
| `T3__track_event_timeline_index.sql` | 用户时间线用的两个索引，只建在父表上 |
| `T5__track_error_issue.sql` | 错误问题单表 `track_error_issue`，支持指派、解决和复发 |

T4 不在基线里。它不能在事务里执行，所以仍是单独的 Java 迁移，在基线之后执行。

## 规则

- 0.2 发布之前，改库直接改基线，改完要重建埋点库。
- 0.2 发布之后，基线不能修改，每次改库都新增一个 `Tn__说明.sql`。
- 新脚本的版本号取 `main` 上最大的号加 1。Java 迁移 T4 也占号，所以基线之后的第一个新脚本是 `T5`。
- 用合并之前的脚本建的埋点库不能原地升级，要重建。

发布清单和主库共用，是仓库根目录的 `db/RELEASED-0.2.sha256`。

完整的规则、重建库的做法和合并脚本的用法，见主库迁移目录的说明：`mugsun-boot-core/src/main/resources/db/migration/README.md`。

### 原 T3 和 T4：时间线索引

基线里原 T3 那一段建两个索引：

| 索引 | 列 |
| --- | --- |
| `idx_event_user_ts_timeline` | `(app_key, user_id, ts, id)` |
| `idx_event_distinct_ts_timeline` | `(app_key, distinct_id, ts, id)` |

`track_event` 是按月分区的表。这一段用 `ON ONLY` 只在父表上建索引，这样不会锁住写入。

原 T1 那一段里的两个旧索引保留不动，它们的最后一列是 `received_at`：

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
