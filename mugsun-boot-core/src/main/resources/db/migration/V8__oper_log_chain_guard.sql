-- 0.1.x：操作日志哈希链多节点防分叉（PostgreSQL / 金仓）。
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
ALTER TABLE sys_oper_log ADD COLUMN IF NOT EXISTS chain_prev VARCHAR(64);
COMMENT ON COLUMN sys_oper_log.chain_prev IS '链唯一兜底：新记录等于 prev_hash，历史记录为空';
CREATE UNIQUE INDEX IF NOT EXISTS uk_oper_log_chain_prev ON sys_oper_log (chain_prev);
