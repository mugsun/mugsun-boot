-- 只读演示模式：三个数据库账号（PostgreSQL；目前演示环境只支持 PG）。
-- 用 owner/超级用户执行，执行前把 {{...}} 占位符替换成强密码，例如：
--   sed -e "s/{{RO_PASSWORD}}/$RO/" -e "s/{{LOG_PASSWORD}}/$LOG/" -e "s/{{TRACK_PASSWORD}}/$TRK/" \
--       -e "s/{{BIZ_DB}}/mugsun/" -e "s/{{TRACK_DB}}/mugsun_track/" -e "s/{{OWNER}}/mugsun/" \
--       docs/demo/sql/demo-accounts.sql > /tmp/demo-accounts.sql
--   psql -U mugsun -d mugsun -v ON_ERROR_STOP=1 -f /tmp/demo-accounts.sql   # 第一段：业务库
-- 第二段（埋点库）需要连到埋点库执行：见下方 \connect。
-- 前提：已用 owner 账号执行过一次迁移（非 demo profile + --mugsun.demo.migrate-only=true），表都已存在。

-- ============ 业务库 {{BIZ_DB}} ============
DO $$
BEGIN
	IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'mugsun_demo_ro') THEN
		CREATE ROLE mugsun_demo_ro LOGIN PASSWORD '{{RO_PASSWORD}}';
	END IF;
	IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'mugsun_demo_log') THEN
		CREATE ROLE mugsun_demo_log LOGIN PASSWORD '{{LOG_PASSWORD}}';
	END IF;
	IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'mugsun_demo_track') THEN
		CREATE ROLE mugsun_demo_track LOGIN PASSWORD '{{TRACK_PASSWORD}}';
	END IF;
END
$$;

GRANT CONNECT ON DATABASE {{BIZ_DB}} TO mugsun_demo_ro, mugsun_demo_log;
GRANT USAGE ON SCHEMA public TO mugsun_demo_ro, mugsun_demo_log;

-- 只读业务账号：全部表只 SELECT；owner 以后新建的表默认也只给 SELECT
GRANT SELECT ON ALL TABLES IN SCHEMA public TO mugsun_demo_ro;
ALTER DEFAULT PRIVILEGES FOR ROLE {{OWNER}} IN SCHEMA public GRANT SELECT ON TABLES TO mugsun_demo_ro;

-- 日志账号：只 INSERT 五张日志表（主键为雪花 id，INSERT 不需要 RETURNING/SELECT）
GRANT INSERT ON sys_login_log, sys_api_log, sys_error_log, sys_oper_log, sys_usual_log TO mugsun_demo_log;


-- ============ 埋点库 {{TRACK_DB}} ============
\connect {{TRACK_DB}}
-- @@TRACK_DB_SECTION@@（测试按此标记切分两段）
GRANT CONNECT ON DATABASE {{TRACK_DB}} TO mugsun_demo_track;
GRANT USAGE ON SCHEMA public TO mugsun_demo_track;
-- 埋点账号只 INSERT、SELECT（会话/身份/事件定义/回放的 upsert 在 demo 下降级为 ON CONFLICT DO NOTHING）
GRANT SELECT, INSERT ON ALL TABLES IN SCHEMA public TO mugsun_demo_track;
ALTER DEFAULT PRIVILEGES FOR ROLE {{OWNER}} IN SCHEMA public GRANT SELECT, INSERT ON TABLES TO mugsun_demo_track;
