# 升级说明：历史日表 UV 回填（distinct_id 口径）

适用：从 0.1.0 升级到包含 PR #16（UV 统一按 `distinct_id` 去重）的版本。

## 为什么要回填

PR #16 之后，概览卡片、今日数据和以后每天新生成的日表都按 `distinct_id`（设备）去重计 UV。
但 `track_stats_day` 里**已经算好的历史日子**仍是旧口径（按登录用户归并），趋势图上过去的日子会和新口径对不上。
回填会用明细表 `track_event` 按新口径重算这些日子的 `uv`。

## 会改什么、不会改什么

- 只改 `track_stats_day.uv` 一列，而且只改和重算值不一样的行；pv、会话数、跳出、时长、事件数都不动，不新增行。
- 明细已被保留期清理掉的日子（当天在 `track_event` 里没有事件）**跳过**，保留原值，不会被改成 0。
- 每改一行，旧值先写进备份表 `track_stats_day_uv_backup`（由新迁移 `T2` 建表），带 `run_id`。
- 一条 SQL 只处理一个应用的一天，备份和更新在同一条语句里完成；不会长时间锁表。
- 可以重复执行：第二次执行时已经一致的行不会再改，也不会再写备份。

## 怎么执行

默认回填最近 **90** 个闭合日（UTC，不含今天），可用 `mugsun.track.uv-backfill.days`（环境变量 `MUGSUN_TRACK_UV_BACKFILL_DAYS`）调整，范围 1–3660。

任选一种：

1. **PowerJob**：新建一个任务，处理器填 `com.mugsun.boot.track.powerjob.TrackUvBackfillProcessor`，手动运行一次。
   任务参数可留空，或写 `{"days": 180}`、`{"days": 30, "appKey": "你的应用"}`。
2. **没有 PowerJob**：设 `MUGSUN_TRACK_UV_BACKFILL_ON_STARTUP=true` 后重启，启动后会在后台跑一遍；日志里出现
   `UV 回填完成：run_id=...` 即完成。完成后把它改回 `false`。

多实例部署时同一时刻只会有一个实例执行（Redis 锁），其他实例跳过。

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
