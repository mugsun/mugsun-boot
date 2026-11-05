-- pg2dm: manual
-- 空间行为分析：sys:track-spatial:list（达梦）。幂等。

INSERT INTO sys_menu (id, parent_id, menu_name, path, component, menu_type, permission, sort, icon, is_public, create_time, is_deleted)
SELECT 1093000000000000018, p.id, '空间行为', '/track/spatial', '/track/spatial', 'C', 'sys:track-spatial:list', 13, 'ri:map-2-line', 0, SYSDATE, 0
FROM sys_menu p
WHERE p.path = '/track' AND p.is_deleted = 0
  AND NOT EXISTS (SELECT 1 FROM sys_menu WHERE id = 1093000000000000018)
  AND NOT EXISTS (SELECT 1 FROM sys_menu WHERE permission = 'sys:track-spatial:list' AND is_deleted = 0);

INSERT INTO sys_role_menu (id, role_id, menu_id, create_time, is_deleted)
SELECT (SELECT COALESCE(MAX(id), 1093000000000100000) FROM sys_role_menu
        WHERE id BETWEEN 1093000000000100000 AND 1093999999999999999)
       + ROWNUM, g.role_id, g.menu_id, SYSDATE, 0
FROM (
	SELECT rm.role_id, nm.id AS menu_id
	FROM sys_role_menu rm
	JOIN sys_menu m ON m.id = rm.menu_id AND m.is_deleted = 0
	JOIN sys_menu nm ON nm.permission = 'sys:track-spatial:list' AND nm.is_deleted = 0
	WHERE rm.is_deleted = 0 AND m.permission = 'sys:track-overview:list'
) g
WHERE NOT EXISTS (SELECT 1 FROM sys_role_menu x
                  WHERE x.role_id = g.role_id AND x.menu_id = g.menu_id AND x.is_deleted = 0);
