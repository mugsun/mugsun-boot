-- V7 前造出旧索引下就存在的数据（openGauss / 达梦 / PG 通用 SQL）。达梦、openGauss 实测见 PR 说明
INSERT INTO sys_tenant (id, tenant_code, tenant_name, is_deleted) VALUES (9100000000000000001, '700001', 'T-A', 0);
INSERT INTO sys_user (id, tenant_id, username, password, nickname, phone, is_deleted) VALUES
 (9100000000000000011, '700001', 'u1', 'x', 'n', NULL, 0),
 (9100000000000000012, '700001', 'u2', 'x', 'n', NULL, 0),
 (9100000000000000013, '700001', 'u3', 'x', 'n', '', 0),
 (9100000000000000014, '700001', 'u4', 'x', 'n', '', 0),
 (9100000000000000015, '700001', 'u5', 'x', 'n', '13800000001', 0),
 (9100000000000000016, '700001', 'u6', 'x', 'n', '13800000001', 1);
INSERT INTO sys_role_menu (id, role_id, menu_id, is_deleted) VALUES (9100000000000000021, 9100000000000000031, 9100000000000000041, 0);
