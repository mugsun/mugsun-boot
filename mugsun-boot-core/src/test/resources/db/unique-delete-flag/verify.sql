-- V7 之后：删除再建、删两次、手机号
-- 租户：删 → 建同编号 → 再删 → 再建
UPDATE sys_tenant SET is_deleted = 1 WHERE id = 9100000000000000001;
INSERT INTO sys_tenant (id, tenant_code, tenant_name, is_deleted) VALUES (9100000000000000002, '700001', 'T-A2', 0);
UPDATE sys_tenant SET is_deleted = 1 WHERE id = 9100000000000000002;
INSERT INTO sys_tenant (id, tenant_code, tenant_name, is_deleted) VALUES (9100000000000000003, '700001', 'T-A3', 0);
-- 用户名：删两次后再建
UPDATE sys_user SET is_deleted = 1 WHERE id = 9100000000000000011;
INSERT INTO sys_user (id, tenant_id, username, password, nickname, is_deleted) VALUES (9100000000000000017, '700001', 'u1', 'x', 'n', 0);
UPDATE sys_user SET is_deleted = 1 WHERE id = 9100000000000000017;
INSERT INTO sys_user (id, tenant_id, username, password, nickname, is_deleted) VALUES (9100000000000000018, '700001', 'u1', 'x', 'n', 0);
-- 角色菜单：撤销 → 授权 → 撤销 → 授权（#95）
UPDATE sys_role_menu SET is_deleted = 1 WHERE id = 9100000000000000021;
INSERT INTO sys_role_menu (id, role_id, menu_id, is_deleted) VALUES (9100000000000000022, 9100000000000000031, 9100000000000000041, 0);
UPDATE sys_role_menu SET is_deleted = 1 WHERE id = 9100000000000000022;
INSERT INTO sys_role_menu (id, role_id, menu_id, is_deleted) VALUES (9100000000000000023, 9100000000000000031, 9100000000000000041, 0);
-- 用户角色、角色部门、租户数据源同理
INSERT INTO sys_user_role (id, user_id, role_id, is_deleted) VALUES (9100000000000000051, 1, 2, 1), (9100000000000000052, 1, 2, 1), (9100000000000000053, 1, 2, 0);
INSERT INTO sys_role_dept (id, role_id, dept_id, is_deleted) VALUES (9100000000000000061, 1, 2, 1), (9100000000000000062, 1, 2, 1), (9100000000000000063, 1, 2, 0);
-- 手机号：NULL、空串可重复；已删除行不占号
INSERT INTO sys_user (id, tenant_id, username, password, nickname, phone, is_deleted) VALUES
 (9100000000000000019, '700001', 'u7', 'x', 'n', NULL, 0),
 (9100000000000000020, '700001', 'u8', 'x', 'n', '', 0),
 (9100000000000000024, '700001', 'u9', 'x', 'n', '13800000001', 1),
 (9100000000000000025, '700002', 'u5', 'x', 'n', '13800000001', 0);
-- 租户数据源：删两次后再建
INSERT INTO sys_tenant_datasource (id, tenant_code, ds_url, ds_username, ds_password, is_deleted) VALUES (9100000000000000201, 't1', 'u', 'u', 'p', 0);
UPDATE sys_tenant_datasource SET is_deleted = 1 WHERE id = 9100000000000000201;
INSERT INTO sys_tenant_datasource (id, tenant_code, ds_url, ds_username, ds_password, is_deleted) VALUES (9100000000000000202, 't1', 'u', 'u', 'p', 0);
UPDATE sys_tenant_datasource SET is_deleted = 1 WHERE id = 9100000000000000202;
INSERT INTO sys_tenant_datasource (id, tenant_code, ds_url, ds_username, ds_password, is_deleted) VALUES (9100000000000000203, 't1', 'u', 'u', 'p', 0);
