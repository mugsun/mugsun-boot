-- 0.2.x：用户细查时间线 (ts, id) 游标索引（TrackUserTimelineService）
-- #23 起时间线按 (ts, id) 倒序游标分页（ts=页面显示的发生时间，同 ts 以 id 定序保翻页稳定），received_at 仅作范围过滤（分区裁剪）。
-- 两条查询入口：userId（登录段 e.user_id 直中）/ distinctId（访客口径），各配一条 (app_key, 身份, ts, id) 复合索引，
-- 等值前缀命中后按索引序倒扫取 LIMIT 行，免排序。
-- T1 的 idx_event_user_timeline / idx_event_distinct_timeline（尾列 received_at，旧游标口径）不在此删除，
-- 已发布脚本不改；是否下线待线上执行计划确认后另起迁移。
-- track_event 为 received_at 月分区父表：父表建索引自动级联到现有及后续分区（分区父表不支持 CONCURRENTLY）。
-- 埋点库仅 PG 一套脚本（信创库关 mugsun.track.flyway-enabled 手工灌库，无 dm/og 方言目录）。
CREATE INDEX IF NOT EXISTS idx_event_user_ts_timeline ON track_event (app_key, user_id, ts, id);
COMMENT ON INDEX idx_event_user_ts_timeline IS '用户细查时间线：app+user 等值，按发生时间 (ts, id) 倒序游标分页';
CREATE INDEX IF NOT EXISTS idx_event_distinct_ts_timeline ON track_event (app_key, distinct_id, ts, id);
COMMENT ON INDEX idx_event_distinct_ts_timeline IS '用户细查时间线（访客口径）：app+distinct 等值，按发生时间 (ts, id) 倒序游标分页';
