-- pg2dm: manual
-- 0.1.1：注册用户可上传附件（达梦）。幂等。
-- 1) 平台租户内置「普通用户」角色（role_code=user）改由迁移播种；原先由 DataInitializer 启动时补，
--    全新库在 V13 时还没有这个角色，拿不到 sys:file:upload。DataInitializer 见到已存在即跳过。
-- 2) 新参数 security.register.register-role-code：自助注册分配的角色，缺省 user；
--    与 security.register.default-role-code 分开（后者保留给其他默认归属场景）。
-- 3) user 角色和两个参数指向的角色补授 sys:file:upload。新建角色默认仍无任何权限。

INSERT INTO sys_role (id, role_name, role_code, sort, data_scope, tenant_id, create_time, is_deleted)
SELECT 1079000000000000001, '普通用户', 'user', 9, 4, '000000', SYSDATE, 0 FROM DUAL
WHERE NOT EXISTS (SELECT 1 FROM sys_role WHERE role_code = 'user' AND tenant_id = '000000' AND is_deleted = 0)
  AND NOT EXISTS (SELECT 1 FROM sys_role WHERE id = 1079000000000000001);

INSERT INTO sys_param (id, param_name, param_key, param_value, remark, create_time, is_deleted)
SELECT 1079000000000000011, '自助注册分配的角色', 'security.register.register-role-code', 'user',
       '自助注册成功后挂的角色编码（平台租户内）；置空则不挂角色', SYSDATE, 0 FROM DUAL
WHERE NOT EXISTS (SELECT 1 FROM sys_param WHERE param_key = 'security.register.register-role-code' AND is_deleted = 0)
  AND NOT EXISTS (SELECT 1 FROM sys_param WHERE id = 1079000000000000011);

INSERT INTO sys_role_menu (id, role_id, menu_id, create_time, is_deleted)
SELECT (SELECT COALESCE(MAX(id), 1079000000000100000) FROM sys_role_menu
        WHERE id BETWEEN 1079000000000100000 AND 1079999999999999999)
       + ROW_NUMBER() OVER (ORDER BY g.role_id),
       g.role_id, g.menu_id, SYSDATE, 0
FROM (
	SELECT DISTINCT r.id AS role_id, fm.id AS menu_id
	FROM sys_role r
	JOIN sys_menu fm ON fm.permission = 'sys:file:upload' AND fm.is_deleted = 0
	WHERE r.is_deleted = 0 AND r.tenant_id = '000000'
	  AND (r.role_code = 'user'
	       OR r.role_code IN (SELECT p.param_value FROM sys_param p WHERE p.is_deleted = 0
	                          AND p.param_key IN ('security.register.register-role-code',
	                                              'security.register.default-role-code')))
) g
WHERE NOT EXISTS (SELECT 1 FROM sys_role_menu x
                  WHERE x.role_id = g.role_id AND x.menu_id = g.menu_id AND x.is_deleted = 0);
