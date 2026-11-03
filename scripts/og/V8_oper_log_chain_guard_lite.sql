-- openGauss-lite 5.1：V8 手工灌库替代脚本（#102）。
-- 与 db/migration-og/V8__oper_log_chain_guard.sql 同语义；本文件保证不用
-- ADD COLUMN IF NOT EXISTS（lite 5.1 语法错误），改用 information_schema DO 块（同 V9/V10）。
--
-- 用法（gsql，库须 DBCOMPATIBILITY 'PG'）：
--   gsql -d mugsun -U gaussdb -f scripts/og/V8_oper_log_chain_guard_lite.sql -1 -v ON_ERROR_STOP=1
-- 其它版本仍按 migration-og 目录顺序执行；V8 用本脚本代替原文件。
-- openGauss 不走 Flyway migrate（社区 PG 插件会 SET ROLE → set role denied），
-- 故无需补登 flyway_schema_history；关 spring.flyway 或勿把 locations 指到 migration-og。

-- ① 链头锁行：落库事务内 SELECT ... FOR UPDATE 串行化各实例的「读链头→写新记录」。
CREATE TABLE IF NOT EXISTS sys_oper_log_chain (
	id          INT       PRIMARY KEY,
	update_time TIMESTAMP
);
INSERT INTO sys_oper_log_chain (id, update_time)
SELECT 1, CURRENT_TIMESTAMP
WHERE NOT EXISTS (SELECT 1 FROM sys_oper_log_chain WHERE id = 1);
COMMENT ON TABLE sys_oper_log_chain IS '操作日志哈希链链头锁（单行 id=1）';

-- ② 唯一兜底：新记录 chain_prev = prev_hash，唯一索引让两条记录无法接在同一链头后。
--    历史记录保持 NULL（不回填，避免历史分叉/重复的 prev_hash 让本迁移失败；NULL 不参与唯一约束）。
DO $$
BEGIN
	IF NOT EXISTS (SELECT 1 FROM information_schema.columns
	               WHERE table_schema = current_schema() AND table_name = 'sys_oper_log' AND column_name = 'chain_prev') THEN
		ALTER TABLE sys_oper_log ADD COLUMN chain_prev VARCHAR(64);
	END IF;
END $$;
COMMENT ON COLUMN sys_oper_log.chain_prev IS '链唯一兜底：新记录等于 prev_hash，历史记录为空';
CREATE UNIQUE INDEX IF NOT EXISTS uk_oper_log_chain_prev ON sys_oper_log (chain_prev);
