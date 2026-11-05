-- 空间行为分析：sys:track-spatial:list。幂等。
-- 菜单挂「埋点分析」目录；已有概览读权的角色补授入口权。

INSERT INTO sys_menu (id, parent_id, menu_name, path, component, menu_type, permission, sort, icon, is_public, create_time, is_deleted)
SELECT 1093000000000000018, p.id, '空间行为', '/track/spatial', '/track/spatial', 'C', 'sys:track-spatial:list', 13, 'ri:map-2-line', 0, now(), 0
FROM sys_menu p
WHERE p.path = '/track' AND p.is_deleted = 0
  AND NOT EXISTS (SELECT 1 FROM sys_menu WHERE id = 1093000000000000018
                  OR (permission = 'sys:track-spatial:list' AND is_deleted = 0));

-- 补授：已有 sys:track-overview:list 的角色获得空间行为入口权（主键用 1093 保留段）
INSERT INTO sys_role_menu (id, role_id, menu_id, create_time, is_deleted)
SELECT (SELECT COALESCE(MAX(id), 1093000000000100000) FROM sys_role_menu
        WHERE id BETWEEN 1093000000000100000 AND 1093999999999999999)
       + ROW_NUMBER() OVER (ORDER BY g.role_id, g.menu_id),
       g.role_id, g.menu_id, now(), 0
FROM (
	SELECT rm.role_id, nm.id AS menu_id
	FROM sys_role_menu rm
	JOIN sys_menu m ON m.id = rm.menu_id AND m.is_deleted = 0
	JOIN sys_menu nm ON nm.permission = 'sys:track-spatial:list' AND nm.is_deleted = 0
	WHERE rm.is_deleted = 0 AND m.permission = 'sys:track-overview:list'
) g
WHERE NOT EXISTS (SELECT 1 FROM sys_role_menu x
                  WHERE x.role_id = g.role_id AND x.menu_id = g.menu_id AND x.is_deleted = 0);
