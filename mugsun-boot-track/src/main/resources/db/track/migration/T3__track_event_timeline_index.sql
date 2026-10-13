-- 0.2.x：用户细查时间线 (ts, id) 游标索引——父表部分（TrackUserTimelineService）
-- 版本号：T2 归 #20（T2__track_stats_day_uv_backup.sql），本脚本为 T3。
-- #23 起时间线按 (ts, id) 倒序游标分页（ts=页面显示的发生时间，同 ts 以 id 定序保翻页稳定），received_at 仅作范围过滤（分区裁剪）。
-- 两条查询入口：userId（e.user_id 直中 / identity 映射 distinct_id 两支 UNION ALL）、distinctId（访客口径），
-- 各配一条 (app_key, 身份, ts, id) 复合索引，等值前缀命中后按索引序倒扫取 LIMIT 行。
-- track_event 为 received_at 月分区父表：父表直接 CREATE INDEX 会在一个事务里对全部分区同步建索引并持 SHARE 锁
-- （阻塞写入，亿级表不可接受；分区父表又不支持 CONCURRENTLY）。故本脚本只建 ON ONLY 父表索引（瞬时，初始 indisvalid=false），
-- 各现存分区由 T4（Java 迁移，executeInTransaction=false）逐分区 CREATE INDEX CONCURRENTLY 后 ATTACH PARTITION，
-- 全部挂齐后父索引自动转 valid。此后 TrackPartitionJob 新建的分区由 PG 自动克隆这两条索引（并校验补齐）。
-- T1 的 idx_event_user_timeline / idx_event_distinct_timeline（尾列 received_at，旧游标口径）不在此删除，
-- 已发布脚本不改；是否下线待线上执行计划确认后另起迁移。
-- 埋点库仅 PG 一套脚本（信创库关 mugsun.track.flyway-enabled 手工灌库，无 dm/og 方言目录）。
CREATE INDEX IF NOT EXISTS idx_event_user_ts_timeline ON ONLY track_event (app_key, user_id, ts, id);
COMMENT ON INDEX idx_event_user_ts_timeline IS '用户细查时间线：app+user 等值，按发生时间 (ts, id) 倒序游标分页';
CREATE INDEX IF NOT EXISTS idx_event_distinct_ts_timeline ON ONLY track_event (app_key, distinct_id, ts, id);
COMMENT ON INDEX idx_event_distinct_ts_timeline IS '用户细查时间线（访客口径）：app+distinct 等值，按发生时间 (ts, id) 倒序游标分页';
