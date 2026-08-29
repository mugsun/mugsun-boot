-- 由 scripts/pg_to_dm.py 从 db/migration 转换（达梦 Oracle 系）。
-- 不引用 pg_catalog / ON CONFLICT / 部分索引 / VALUES 行构造器。
-- pg2dm: manual —— 本文件含手工修正（is_leader 需显式 DEFAULT 0），转换器不再覆盖。

-- 用户档案对齐：真实姓名/性别/生日/工号/直属主管/是否主管（头像列已在 V61）
ALTER TABLE sys_user ADD real_name VARCHAR(64);
ALTER TABLE sys_user ADD sex SMALLINT;
ALTER TABLE sys_user ADD birthday DATE;
ALTER TABLE sys_user ADD code VARCHAR(64);
ALTER TABLE sys_user ADD leader_id BIGINT;
ALTER TABLE sys_user ADD is_leader SMALLINT DEFAULT 0 NOT NULL;

COMMENT ON COLUMN sys_user.real_name IS '真实姓名';
COMMENT ON COLUMN sys_user.sex IS '性别：0 未知 / 1 男 / 2 女';
COMMENT ON COLUMN sys_user.birthday IS '生日';
COMMENT ON COLUMN sys_user.code IS '工号/用户编号';
COMMENT ON COLUMN sys_user.leader_id IS '直属主管用户 id';
COMMENT ON COLUMN sys_user.is_leader IS '是否主管：1 是 / 0 否（可供流程 deptLeader 等选用）';

-- 性别字典见 V77（本脚本曾用 id 与 notice_category 冲突，已迁出）
