-- 0.1.1：下线 AI「MCP 工具箱」里的「对外暴露」（MCP Server 列表与开关）。幂等。
-- 内置 MCP Server 已在 mugsun-boot#89 移除，/system/ai/mcp/server/* 接口随之下线（404）；这里把对应按钮权限 ai:mcp:server 逻辑删除，
-- 并撤掉角色上的该授权，菜单管理与角色授权树里不再出现。不删 ai_mcp_tool 表与数据，不改已发布的 V1。
UPDATE sys_role_menu SET is_deleted = 1, update_time = now()
WHERE is_deleted = 0
  AND menu_id IN (SELECT id FROM sys_menu WHERE permission = 'ai:mcp:server');

UPDATE sys_menu SET is_deleted = 1, update_time = now()
WHERE permission = 'ai:mcp:server' AND is_deleted = 0;
