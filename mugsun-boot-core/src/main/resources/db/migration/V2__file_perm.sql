-- 0.1.1：附件越权治理（原 B4 / PR #11 误追加进已发布 V1，现移到这里）
-- 幂等：部分开发库跑过「带 B4 的 V1」并 flyway repair 过，列/权限/授权可能已存在。

-- 附件归属：上传者用户 id（历史行为空，仅 sys:file:manage 可操作其私有文件）
ALTER TABLE sys_attach ADD COLUMN IF NOT EXISTS create_user BIGINT;
COMMENT ON COLUMN sys_attach.create_user IS '上传者用户 id';

-- 附件按钮权限（menu_type=F，挂「附件管理」1200000000000000112）
INSERT INTO sys_menu (id, parent_id, menu_name, menu_type, permission, sort, create_time, is_deleted) VALUES
(1077000000000000001, 1200000000000000112, '管理全部附件', 'F', 'sys:file:manage', 1, now(), 0),
(1077000000000000002, 1200000000000000112, '上传公开附件', 'F', 'sys:file:public', 2, now(), 0),
(1077000000000000003, 1200000000000000112, '删除附件',     'F', 'sys:file:remove', 3, now(), 0)
ON CONFLICT (id) DO NOTHING;

-- 授给管理员角色（role_code=admin；运行期 admin 另经 StpInterface 通配 *，此处落库供角色授权页回显）
INSERT INTO sys_role_menu (id, role_id, menu_id, create_time, is_deleted)
SELECT v.rm_id, r.role_id, v.menu_id, now(), 0
FROM (VALUES (1077000000000000101, 1077000000000000001),
             (1077000000000000102, 1077000000000000002),
             (1077000000000000103, 1077000000000000003)) AS v(rm_id, menu_id)
CROSS JOIN (SELECT MIN(id) AS role_id FROM sys_role WHERE role_code = 'admin' AND is_deleted = 0 HAVING COUNT(*) > 0) r
WHERE NOT EXISTS (SELECT 1 FROM sys_role_menu x WHERE x.id = v.rm_id
                  OR (x.role_id = r.role_id AND x.menu_id = v.menu_id))
ON CONFLICT (id) DO NOTHING;
