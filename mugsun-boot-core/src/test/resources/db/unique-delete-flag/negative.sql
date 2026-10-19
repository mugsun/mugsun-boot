-- 每条单独执行，都必须撞唯一键（未删除的行仍唯一）
INSERT INTO sys_tenant (id, tenant_code, tenant_name, is_deleted) VALUES (9100000000000000004, '700001', 'dup', 0);
INSERT INTO sys_user (id, tenant_id, username, password, nickname, is_deleted) VALUES (9100000000000000026, '700001', 'u1', 'x', 'n', 0);
INSERT INTO sys_role_menu (id, role_id, menu_id, is_deleted) VALUES (9100000000000000027, 9100000000000000031, 9100000000000000041, 0);
INSERT INTO sys_user_role (id, user_id, role_id, is_deleted) VALUES (9100000000000000054, 1, 2, 0);
INSERT INTO sys_role_dept (id, role_id, dept_id, is_deleted) VALUES (9100000000000000064, 1, 2, 0);
INSERT INTO sys_user (id, tenant_id, username, password, nickname, phone, is_deleted) VALUES (9100000000000000028, '700001', 'u10', 'x', 'n', '13800000001', 0);
UPDATE sys_user SET is_deleted = 0 WHERE id = 9100000000000000016;
INSERT INTO sys_tenant_datasource (id, tenant_code, ds_url, ds_username, ds_password, is_deleted) VALUES (9100000000000000204, 't1', 'u', 'u', 'p', 0);
