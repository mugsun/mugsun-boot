-- #(functionName) 菜单与按钮权限（代码生成产物；父菜单 parent_id 可按需调整）
INSERT INTO sys_menu (id, parent_id, menu_name, path, component, menu_type, permission, sort, create_time, is_deleted) VALUES
(#(menuId), #(parentMenuId), '#(functionName)', '#(businessKebab)', '/#(module)/#(businessKebab)', 'C', '#(permPrefix):list', 90, now(), 0)
ON CONFLICT (id) DO NOTHING;
INSERT INTO sys_menu (id, parent_id, menu_name, menu_type, permission, sort, create_time, is_deleted) VALUES
(#(addBtnId), #(menuId), '新增', 'F', '#(permPrefix):save', 1, now(), 0),
(#(removeBtnId), #(menuId), '删除', 'F', '#(permPrefix):remove', 2, now(), 0)
ON CONFLICT (id) DO NOTHING;
