-- pg2dm: manual
-- 错误问题单写权限：sys:track-error-issue:edit（达梦）。幂等。

INSERT INTO sys_menu (id, parent_id, menu_name, menu_type, permission, sort, create_time, is_deleted)
SELECT 1093000000000000016,
       (SELECT MIN(id) FROM sys_menu WHERE permission = 'sys:track-error:list' AND is_deleted = 0),
       '错误问题单管理', 'F', 'sys:track-error-issue:edit', 1, SYSDATE, 0 FROM DUAL
WHERE EXISTS (SELECT 1 FROM sys_menu WHERE permission = 'sys:track-error:list' AND is_deleted = 0)
  AND NOT EXISTS (SELECT 1 FROM sys_menu WHERE id = 1093000000000000016)
  AND NOT EXISTS (SELECT 1 FROM sys_menu WHERE permission = 'sys:track-error-issue:edit' AND is_deleted = 0);

INSERT INTO sys_role_menu (id, role_id, menu_id, create_time, is_deleted)
SELECT (SELECT COALESCE(MAX(id), 1093000000000100000) FROM sys_role_menu
        WHERE id BETWEEN 1093000000000100000 AND 1093999999999999999)
       + ROWNUM, g.role_id, g.menu_id, SYSDATE, 0
FROM (
	SELECT rm.role_id, nm.id AS menu_id
	FROM sys_role_menu rm
	JOIN sys_menu m ON m.id = rm.menu_id AND m.is_deleted = 0
	JOIN sys_menu nm ON nm.permission = 'sys:track-error-issue:edit' AND nm.is_deleted = 0
	WHERE rm.is_deleted = 0 AND m.permission = 'sys:track-error:list'
) g
WHERE NOT EXISTS (SELECT 1 FROM sys_role_menu x
                  WHERE x.role_id = g.role_id AND x.menu_id = g.menu_id AND x.is_deleted = 0);
