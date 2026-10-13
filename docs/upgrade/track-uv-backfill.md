# 升级说明：历史日表 UV 回填（distinct_id 口径）

适用：从 0.1.0 升级到包含 PR #16（UV 统一按 `distinct_id` 去重）的版本。

## 为什么要回填

PR #16 之后，概览卡片、今日数据和以后每天新生成的日表都按 `distinct_id`（设备）去重计 UV。
但 `track_stats_day` 里**已经算好的历史日子**仍是旧口径（按登录用户归并），趋势图上过去的日子会和新口径对不上。
回填会用明细表 `track_event` 按新口径重算这些日子的 `uv`。

## 会改什么、不会改什么

- 只改 `track_stats_day.uv` 一列，而且只改和重算值不一样的行；pv、会话数、跳出、时长、事件数都不动，不新增行。
- 只处理范围内**有日表行**的日子；有日表行但明细已被保留期清理（当天在 `track_event` 里没有事件）的日子跳过，保留原值，不会被改成 0。
- 每改一行，旧值先写进备份表 `track_stats_day_uv_backup`（新迁移 `T2` 建表），带 `run_id`。
- 可以重复执行：第二次执行时已经一致的行不会再改，也不会再写备份。

## 对线上库的影响和限速

- 每个「应用 × 天」一个事务：一条查询只扫一次当天明细（`GROUPING SETS` 同时算总览和四个维度的 UV），再用一条语句备份并更新变化的行。事务内设 `statement_timeout`（默认 120 秒）和 `work_mem`（默认 256MB，减少 `GROUPING SETS` 排序落盘）。
- **单天超时不会卡住整个回填**：某个「应用 × 天」超时（SQLState 57014）只回滚这一天，日志打 WARN（`UV 回填超时 ... app=... day=...`），该应用的游标不推进、其余日子留到下次，继续处理下一个应用；本次结果为「UV 回填未完成」并列出超时的 `应用@日期`。某个大应用每天都超时时，用 `app-statement-timeout` 单独给它放宽。
- 每处理完一个「应用 × 天」停 `sleep-millis`（默认 300 毫秒）。
- 默认只在**北京时间 02:00–07:00** 运行；不在时段内不启动，跑到时段末尾自动暂停。
- 日期从昨天往前倒序处理，进度写在 `track_rollup_cursor`（`job_key = uv_backfill`）。中断、暂停或锁丢失后再执行会从断点继续；一个应用全部完成后游标删除。
- 独立 Redis 锁（默认 TTL 600 秒，每处理完一个「应用 × 天」续期）。多实例同一时刻只有一个在跑；**Redis 不可用时不执行**。

## 配置

```yaml
mugsun:
  track:
    uv-backfill:
      days: 90                    # MUGSUN_TRACK_UV_BACKFILL_DAYS，1–3660
      sleep-millis: 300           # MUGSUN_TRACK_UV_BACKFILL_SLEEP_MILLIS
      statement-timeout-seconds: 120
      app-statement-timeout:      # 按 appKey 放宽单天超时，例：
        big-app: 600s
      work-mem: 256MB             # MUGSUN_TRACK_UV_BACKFILL_WORK_MEM，留空沿用库默认
      lock-ttl-seconds: 600
      window-enabled: true        # MUGSUN_TRACK_UV_BACKFILL_WINDOW_ENABLED，false 表示不限时段
      window-start: "02:00"
      window-end: "07:00"
      window-zone: Asia/Shanghai
      on-startup: false           # MUGSUN_TRACK_UV_BACKFILL_ON_STARTUP
```

## 上线前实跑清单

1. 在从库或备份库上找一个数据量最大的应用和一天，检查重算查询的计划和耗时：

   ```sql
   EXPLAIN (ANALYZE, BUFFERS)
   SELECT GROUPING(e.event_name, left(coalesce(nullif(e.route_path, ''), e.url_path), 255), e.referrer_domain, e.device) AS g,
          count(DISTINCT e.distinct_id) AS uv, count(*) AS cnt
   FROM track_event e
   WHERE e.app_key = '你的应用' AND e.received_at >= '2026-10-01T00:00:00Z' AND e.received_at < '2026-10-02T00:00:00Z'
   GROUP BY GROUPING SETS ((), (e.event_name), (left(coalesce(nullif(e.route_path, ''), e.url_path), 255)),
                          (e.referrer_domain), (e.device));
   ```

   执行前先在同一会话里 `SET work_mem = '256MB';`（与 `work-mem` 配置一致）。逐项确认：
   - 只扫当天所在的**单个**月分区，走 `app_key, received_at` 相关索引（而不是全分区顺序扫描）；
   - 计划里没有 `external merge`，`Buffers` 里没有（或很少）`temp read/written`；有的话调大 `work-mem` 再看；
   - 总耗时远小于 `statement-timeout-seconds`（120 秒），否则给这个应用配 `app-statement-timeout`；
   - `shared read` 低于旧做法（总览 + 四个维度各扫一遍，共 5 条查询）之和。
2. 用一个小应用试跑一天：PowerJob 参数 `{"days": 1, "appKey": "小应用"}`，看日志里的改动行数和备份表。
3. 同样参数再跑一次，确认日志里「改动 0 行」。
4. 再按默认 90 天（或需要的天数）在低峰时段跑全量。

## 可选：加索引

迁移里**不建索引**（大表建索引会长时间锁表）。如果第 1 步发现当天明细没有合适索引，可以在低峰期对需要的月分区逐个**并发**建索引（分区表的父表不支持 `CONCURRENTLY`，要对每个分区单独建）：

```sql
-- 对每个需要回填的月分区执行，分区名以 \d+ track_event 列出的为准
CREATE INDEX CONCURRENTLY IF NOT EXISTS idx_track_event_202609_app_recv
    ON track_event_2026_09 (app_key, received_at);
```

建完后重新跑第 1 步的 `EXPLAIN`，确认用上了索引。回填结束后如不需要可 `DROP INDEX CONCURRENTLY` 删除。

## 怎么执行

**高峰期不要打开 `on-startup`。** 任选一种：

1. **PowerJob**：新建一个任务，处理器填 `com.mugsun.boot.track.powerjob.TrackUvBackfillProcessor`，**配成每天 02:05 定时运行，直到日志出现 `UV 回填完成`** 再停用。
   只有「UV 回填完成」时 PowerJob 结果才是成功；不在运行时段、跑到时段末尾暂停、锁丢失、有「应用 × 天」超时，结果都显示**失败（未完成）**，这是提醒还要继续跑，不是出错，下次定时会从断点续跑。
   任务参数可留空，或写 `{"days": 180}`、`{"days": 30, "appKey": "你的应用"}`。
2. **没有 PowerJob**：设 `MUGSUN_TRACK_UV_BACKFILL_ON_STARTUP=true` 后在低峰期重启，启动后会在后台跑一遍（仍受运行时段限制）；日志里出现
   `UV 回填完成：run_id=...` 即完成。完成后把它改回 `false`。

日志里的「重算 N 天」是实际重算的天数，「有日表行但明细已清理、保留原值 M 天」是跳过的天数；范围内没有日表行的日子不计入。

## 执行前建议

`track_stats_day_uv_backup` 已经保存了每一行的旧值。如果还想要一份整表备份：

```sql
CREATE TABLE track_stats_day_bak_20261013 AS SELECT * FROM track_stats_day;
```

## 怎么还原

按日志里的 `run_id` 还原那一批：

```sql
UPDATE track_stats_day d SET uv = b.old_uv, update_time = now()
FROM track_stats_day_uv_backup b
WHERE b.run_id = '日志里的 run_id' AND d.id = b.stats_day_id;
```

注意：还原后再执行回填，又会改回新口径。

## 清理备份表

确认新口径没问题后（建议观察一两周），可以清理备份：

```sql
DELETE FROM track_stats_day_uv_backup WHERE backup_time < now() - interval '30 days';
-- 或整表清空
TRUNCATE track_stats_day_uv_backup;
```

## 列注释

T1 已发布不改，`track_stats_day.uv` 的列注释仍写着旧的归并口径；实际口径以本文和 `TrackConstants.UV_EXPR` 为准。
