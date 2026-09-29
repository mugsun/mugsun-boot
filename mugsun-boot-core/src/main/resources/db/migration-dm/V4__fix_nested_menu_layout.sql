-- pg2dm: manual
-- 修复二级目录误配 /index/index 导致内容区嵌套双菜单

UPDATE sys_menu
SET component = NULL, update_time = SYSDATE
WHERE menu_type = 'M'
  AND parent_id <> 0
  AND component = '/index/index'
  AND is_deleted = 0;
