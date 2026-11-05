-- 错误问题单写权限：sys:track-error-issue:edit（读复用 sys:track-error:list）。幂等。
-- 按钮挂「错误监控」页下；已有错误监控读权的角色补授写权。

INSERT INTO sys_menu (id, parent_id, menu_name, menu_type, permission, sort, create_time, is_deleted)
SELECT 1093000000000000016,
       (SELECT MIN(id) FROM sys_menu WHERE permission = 'sys:track-error:list' AND is_deleted = 0),
       '错误问题单管理', 'F', 'sys:track-error-issue:edit', 1, now(), 0
WHERE EXISTS (SELECT 1 FROM sys_menu WHERE permission = 'sys:track-error:list' AND is_deleted = 0)
  AND NOT EXISTS (SELECT 1 FROM sys_menu WHERE id = 1093000000000000016
                  OR (permission = 'sys:track-error-issue:edit' AND is_deleted = 0));

-- 补授：已有 sys:track-error:list 的角色获得写权（主键用 1093 保留段）
INSERT INTO sys_role_menu (id, role_id, menu_id, create_time, is_deleted)
SELECT (SELECT COALESCE(MAX(id), 1093000000000100000) FROM sys_role_menu
        WHERE id BETWEEN 1093000000000100000 AND 1093999999999999999)
       + ROW_NUMBER() OVER (ORDER BY g.role_id, g.menu_id),
       g.role_id, g.menu_id, now(), 0
FROM (
	SELECT rm.role_id, nm.id AS menu_id
	FROM sys_role_menu rm
	JOIN sys_menu m ON m.id = rm.menu_id AND m.is_deleted = 0
	JOIN sys_menu nm ON nm.permission = 'sys:track-error-issue:edit' AND nm.is_deleted = 0
	WHERE rm.is_deleted = 0 AND m.permission = 'sys:track-error:list'
) g
WHERE NOT EXISTS (SELECT 1 FROM sys_role_menu x
                  WHERE x.role_id = g.role_id AND x.menu_id = g.menu_id AND x.is_deleted = 0);
