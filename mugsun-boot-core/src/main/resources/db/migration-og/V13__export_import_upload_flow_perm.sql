-- 0.1.1：导出/导入/上传/表单/流程按钮权限码（openGauss-lite 5.1）。幂等。
-- 1) 用户导出导入拆出 sys:user:export / sys:user:import（原为 sys:user:list / sys:user:add）
-- 2) 附件上传 sys:file:upload（原先登录即可）
-- 3) 表单设计保存删除、流程部署设计发布改为方法级注解，权限码与原兜底守卫派生的码一致
-- 升级不掉权限：
-- - 已有 sys:user:list 的角色补授导出码，已有 sys:user:add 的角色补授导入码
-- - sys:file:upload 补授给全部现有角色（意见反馈等页面也在用上传）
-- - 表单、流程的新按钮只补授给各租户的 admin 角色（原先非管理员本就会被兜底守卫拒绝）。
--   admin 现在经 MugsunStpInterface 直接拿到通配 *，这条补授当前是冗余的，
--   为将来收回通配预留；脚本已在测试库执行过，不再改语句，只改注释
-- 父级按业务键 path 找（全新库与升级库的主键不同）；父菜单不存在时跳过。

INSERT INTO sys_menu (id, parent_id, menu_name, menu_type, permission, sort, create_time, is_deleted)
SELECT 1078000000000000001, (SELECT MIN(id) FROM sys_menu WHERE path = '/system/user' AND is_deleted = 0),
       '导出用户', 'F', 'sys:user:export', 6, now(), 0
WHERE EXISTS (SELECT 1 FROM sys_menu WHERE path = '/system/user' AND is_deleted = 0)
  AND NOT EXISTS (SELECT 1 FROM sys_menu WHERE id = 1078000000000000001 OR (permission = 'sys:user:export' AND is_deleted = 0));
INSERT INTO sys_menu (id, parent_id, menu_name, menu_type, permission, sort, create_time, is_deleted)
SELECT 1078000000000000002, (SELECT MIN(id) FROM sys_menu WHERE path = '/system/user' AND is_deleted = 0),
       '导入用户', 'F', 'sys:user:import', 7, now(), 0
WHERE EXISTS (SELECT 1 FROM sys_menu WHERE path = '/system/user' AND is_deleted = 0)
  AND NOT EXISTS (SELECT 1 FROM sys_menu WHERE id = 1078000000000000002 OR (permission = 'sys:user:import' AND is_deleted = 0));
INSERT INTO sys_menu (id, parent_id, menu_name, menu_type, permission, sort, create_time, is_deleted)
SELECT 1078000000000000003, (SELECT MIN(id) FROM sys_menu WHERE path = '/system/attach' AND is_deleted = 0),
       '上传附件', 'F', 'sys:file:upload', 4, now(), 0
WHERE EXISTS (SELECT 1 FROM sys_menu WHERE path = '/system/attach' AND is_deleted = 0)
  AND NOT EXISTS (SELECT 1 FROM sys_menu WHERE id = 1078000000000000003 OR (permission = 'sys:file:upload' AND is_deleted = 0));
INSERT INTO sys_menu (id, parent_id, menu_name, menu_type, permission, sort, create_time, is_deleted)
SELECT 1078000000000000004, (SELECT MIN(id) FROM sys_menu WHERE path = '/system/form-designer' AND is_deleted = 0),
       '保存表单', 'F', 'sys:form:save', 1, now(), 0
WHERE EXISTS (SELECT 1 FROM sys_menu WHERE path = '/system/form-designer' AND is_deleted = 0)
  AND NOT EXISTS (SELECT 1 FROM sys_menu WHERE id = 1078000000000000004 OR (permission = 'sys:form:save' AND is_deleted = 0));
INSERT INTO sys_menu (id, parent_id, menu_name, menu_type, permission, sort, create_time, is_deleted)
SELECT 1078000000000000005, (SELECT MIN(id) FROM sys_menu WHERE path = '/system/form-designer' AND is_deleted = 0),
       '删除表单', 'F', 'sys:form:remove', 2, now(), 0
WHERE EXISTS (SELECT 1 FROM sys_menu WHERE path = '/system/form-designer' AND is_deleted = 0)
  AND NOT EXISTS (SELECT 1 FROM sys_menu WHERE id = 1078000000000000005 OR (permission = 'sys:form:remove' AND is_deleted = 0));
INSERT INTO sys_menu (id, parent_id, menu_name, menu_type, permission, sort, create_time, is_deleted)
SELECT 1078000000000000006, (SELECT MIN(id) FROM sys_menu WHERE path = '/system/flow-def' AND is_deleted = 0),
       '部署流程', 'F', 'sys:flow:deploy', 1, now(), 0
WHERE EXISTS (SELECT 1 FROM sys_menu WHERE path = '/system/flow-def' AND is_deleted = 0)
  AND NOT EXISTS (SELECT 1 FROM sys_menu WHERE id = 1078000000000000006 OR (permission = 'sys:flow:deploy' AND is_deleted = 0));
INSERT INTO sys_menu (id, parent_id, menu_name, menu_type, permission, sort, create_time, is_deleted)
SELECT 1078000000000000007, (SELECT MIN(id) FROM sys_menu WHERE path = '/system/flow-def' AND is_deleted = 0),
       '发布/下线流程', 'F', 'sys:flow:definition', 2, now(), 0
WHERE EXISTS (SELECT 1 FROM sys_menu WHERE path = '/system/flow-def' AND is_deleted = 0)
  AND NOT EXISTS (SELECT 1 FROM sys_menu WHERE id = 1078000000000000007 OR (permission = 'sys:flow:definition' AND is_deleted = 0));
INSERT INTO sys_menu (id, parent_id, menu_name, menu_type, permission, sort, create_time, is_deleted)
SELECT 1078000000000000008, (SELECT MIN(id) FROM sys_menu WHERE path = '/system/flow-def' AND is_deleted = 0),
       '设计流程', 'F', 'sys:flow:design', 3, now(), 0
WHERE EXISTS (SELECT 1 FROM sys_menu WHERE path = '/system/flow-def' AND is_deleted = 0)
  AND NOT EXISTS (SELECT 1 FROM sys_menu WHERE id = 1078000000000000008 OR (permission = 'sys:flow:design' AND is_deleted = 0));
INSERT INTO sys_menu (id, parent_id, menu_name, menu_type, permission, sort, create_time, is_deleted)
SELECT 1078000000000000009, (SELECT MIN(id) FROM sys_menu WHERE path = '/system/flow-graph' AND is_deleted = 0),
       '图形设计流程', 'F', 'sys:flow:design-graph', 1, now(), 0
WHERE EXISTS (SELECT 1 FROM sys_menu WHERE path = '/system/flow-graph' AND is_deleted = 0)
  AND NOT EXISTS (SELECT 1 FROM sys_menu WHERE id = 1078000000000000009 OR (permission = 'sys:flow:design-graph' AND is_deleted = 0));

-- 补授。主键用 1078 保留段，从段内已用最大值往后排，不取全表 MAX(id)，避免撞上后续脚本或种子里的固定主键
INSERT INTO sys_role_menu (id, role_id, menu_id, create_time, is_deleted)
SELECT (SELECT COALESCE(MAX(id), 1078000000000100000) FROM sys_role_menu
        WHERE id BETWEEN 1078000000000100000 AND 1078999999999999999)
       + ROW_NUMBER() OVER (ORDER BY g.role_id, g.menu_id),
       g.role_id, g.menu_id, now(), 0
FROM (
	SELECT rm.role_id, nm.id AS menu_id
	FROM sys_role_menu rm
	JOIN sys_menu m ON m.id = rm.menu_id AND m.is_deleted = 0
	JOIN (SELECT 'sys:user:list' AS src, 'sys:user:export' AS dst
	      UNION ALL SELECT 'sys:user:add', 'sys:user:import') n ON m.permission = n.src
	JOIN sys_menu nm ON nm.permission = n.dst AND nm.is_deleted = 0
	WHERE rm.is_deleted = 0
	UNION
	SELECT r.id, fm.id
	FROM sys_role r
	JOIN sys_menu fm ON fm.permission = 'sys:file:upload' AND fm.is_deleted = 0
	WHERE r.is_deleted = 0
	UNION
	SELECT r.id, am.id
	FROM sys_role r
	JOIN sys_menu am ON am.id BETWEEN 1078000000000000004 AND 1078000000000000009
	                AND am.permission IN ('sys:form:save', 'sys:form:remove', 'sys:flow:deploy', 'sys:flow:definition', 'sys:flow:design', 'sys:flow:design-graph') AND am.is_deleted = 0
	WHERE r.role_code = 'admin' AND r.is_deleted = 0
) g
WHERE NOT EXISTS (SELECT 1 FROM sys_role_menu x
                  WHERE x.role_id = g.role_id AND x.menu_id = g.menu_id AND x.is_deleted = 0);
