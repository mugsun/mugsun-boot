-- 0.1.1：低代码表单按租户隔离。sys_form、sys_form_data 补 tenant_id，表单标识改为租户内唯一。幂等。
-- 背景：两张表没有 tenant_id，持久层不加租户条件，租户管理员能列出、修改、删除别的租户的表单和全部填报，
--       登录用户能往别的租户的表单里提交。
-- 存量行看不出原来属于哪个租户，表单和填报一律归平台租户 000000。
-- 要把某个表单还给租户：按表单标识把两张表的 tenant_id 一起改成该租户编号。
ALTER TABLE sys_form ADD COLUMN IF NOT EXISTS tenant_id VARCHAR(12);
ALTER TABLE sys_form_data ADD COLUMN IF NOT EXISTS tenant_id VARCHAR(12);
UPDATE sys_form SET tenant_id = '000000' WHERE tenant_id IS NULL;
UPDATE sys_form_data SET tenant_id = '000000' WHERE tenant_id IS NULL;

DROP INDEX IF EXISTS uk_form_key;
CREATE UNIQUE INDEX IF NOT EXISTS uk_form_tenant_key ON sys_form (tenant_id, form_key) WHERE is_deleted = 0;

COMMENT ON COLUMN sys_form.tenant_id IS '租户编号';
COMMENT ON COLUMN sys_form_data.tenant_id IS '租户编号（与所属表单相同）';
