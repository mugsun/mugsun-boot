-- 0.1.1：附件越权治理（openGauss-lite 5.1；原 B4 误追加进已发布 V1，现移到这里）。幂等。
DO $$
BEGIN
	IF NOT EXISTS (SELECT 1 FROM information_schema.columns
	               WHERE table_schema = current_schema() AND table_name = 'sys_attach' AND column_name = 'create_user') THEN
		ALTER TABLE sys_attach ADD COLUMN create_user BIGINT;
	END IF;
END $$;
COMMENT ON COLUMN sys_attach.create_user IS '上传者用户 id';

INSERT INTO sys_menu (id, parent_id, menu_name, menu_type, permission, sort, create_time, is_deleted) VALUES
(1077000000000000001, 1200000000000000112, '管理全部附件', 'F', 'sys:file:manage', 1, now(), 0),
(1077000000000000002, 1200000000000000112, '上传公开附件', 'F', 'sys:file:public', 2, now(), 0),
(1077000000000000003, 1200000000000000112, '删除附件',     'F', 'sys:file:remove', 3, now(), 0) ON DUPLICATE KEY UPDATE NOTHING;

INSERT INTO sys_role_menu (id, role_id, menu_id, create_time, is_deleted)
SELECT 1077000000000000101, MIN(r.id), 1077000000000000001, now(), 0 FROM sys_role r WHERE r.role_code = 'admin' AND r.is_deleted = 0
  AND NOT EXISTS (SELECT 1 FROM sys_role_menu x WHERE x.id = 1077000000000000101 OR (x.role_id = r.id AND x.menu_id = 1077000000000000001)) HAVING COUNT(*) > 0;
INSERT INTO sys_role_menu (id, role_id, menu_id, create_time, is_deleted)
SELECT 1077000000000000102, MIN(r.id), 1077000000000000002, now(), 0 FROM sys_role r WHERE r.role_code = 'admin' AND r.is_deleted = 0
  AND NOT EXISTS (SELECT 1 FROM sys_role_menu x WHERE x.id = 1077000000000000102 OR (x.role_id = r.id AND x.menu_id = 1077000000000000002)) HAVING COUNT(*) > 0;
INSERT INTO sys_role_menu (id, role_id, menu_id, create_time, is_deleted)
SELECT 1077000000000000103, MIN(r.id), 1077000000000000003, now(), 0 FROM sys_role r WHERE r.role_code = 'admin' AND r.is_deleted = 0
  AND NOT EXISTS (SELECT 1 FROM sys_role_menu x WHERE x.id = 1077000000000000103 OR (x.role_id = r.id AND x.menu_id = 1077000000000000003)) HAVING COUNT(*) > 0;
